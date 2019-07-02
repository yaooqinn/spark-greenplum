/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.greenplum

import java.io._
import java.nio.charset.StandardCharsets
import java.sql.{Connection, Date, Timestamp}
import java.util.UUID
import java.util.concurrent.{TimeoutException, TimeUnit}

import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.util.Try

import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection

import org.apache.spark.SparkEnv
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils
import org.apache.spark.sql.types._
import org.apache.spark.util.{LongAccumulator, ThreadUtils, Utils}

object GreenplumUtils extends Logging {

  def makeConverter(dataType: DataType): (Row, Int) => String = dataType match {
    case StringType => (r: Row, i: Int) => r.getString(i)
    case BooleanType => (r: Row, i: Int) => r.getBoolean(i).toString
    case ByteType => (r: Row, i: Int) => r.getByte(i).toString
    case ShortType => (r: Row, i: Int) => r.getShort(i).toString
    case IntegerType => (r: Row, i: Int) => r.getInt(i).toString
    case LongType => (r: Row, i: Int) => r.getLong(i).toString
    case FloatType => (r: Row, i: Int) => r.getFloat(i).toString
    case DoubleType => (r: Row, i: Int) => r.getDouble(i).toString
    case DecimalType() => (r: Row, i: Int) => r.getDecimal(i).toString

    case DateType =>
      (r: Row, i: Int) => r.getAs[Date](i).toString

    case TimestampType => (r: Row, i: Int) => r.getAs[Timestamp](i).toString

    case BinaryType => (r: Row, i: Int) =>
      new String(r.getAs[Array[Byte]](i), StandardCharsets.UTF_8)

    case udt: UserDefinedType[_] => makeConverter(udt.sqlType)
    case _ => (row: Row, ordinal: Int) => row.get(ordinal).toString
  }

  def convertRow(
      row: Row,
      length: Int,
      delimiter: String,
      valueConverters: Array[(Row, Int) => String]): Array[Byte] = {
    var i = 0
    val values = new Array[String](length)
    while (i < length) {
      if (!row.isNullAt(i)) {
        values(i) = convertValue(valueConverters(i).apply(row, i), delimiter.charAt(0))
      } else {
        values(i) = "NULL"
      }
      i += 1
    }
    (values.mkString(delimiter) + "\n").getBytes("UTF-8")
  }

  def convertValue(str: String, delimiter: Char): String = {
    str.flatMap {
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case `delimiter` => s"\\$delimiter"
      case c if c == 0 => "" // If this char is an empty character, drop it.
      case c => s"$c"
    }
  }

  /**
   * https://www.postgresql.org/docs/9.2/sql-copy.html
   *
   * Copy data to greenplum in a single transaction.
   *
   * @param df the [[DataFrame]] will be copy to the Greenplum
   * @param schema the table schema in Greemnplum
   * @param options Options for the Greenplum data source
   */
  def transactionalCopy(
      df: DataFrame,
      schema: StructType,
      options: GreenplumOptions): Unit = {
    val randomString = UUID.randomUUID().toString.filterNot(_ == '-')
    val canonicalTblName = TableNameExtractor.extract(options.table)
    val schemaPrefix = canonicalTblName.schema.map(_ + ".").getOrElse("")
    val rawTblName = canonicalTblName.rawName
    val suffix = "sparkGpTmp"
    val quote = "\""

    val tempTable = s"$schemaPrefix$quote${rawTblName}_${randomString}_$suffix$quote"
    val strSchema = JdbcUtils.schemaString(df, options.url, options.createTableColumnTypes)
    val createTempTbl = s"CREATE TABLE $tempTable ($strSchema) ${options.createTableOptions}"

    // Stage 1. create a _sparkGpTmp table as a shadow of the target Greenplum table. If this stage
    // fails, the whole process will abort.
    val conn = JdbcUtils.createConnectionFactory(options)()
    try {
      executeStatement(conn, createTempTbl)
    } finally {
      closeConnSilent(conn)
    }

    // Stage 2. Spark executors run copy task to Greenplum, and will increase the accumulator if
    // each task successfully copied.
    val accumulator = df.sparkSession.sparkContext.longAccumulator("copySuccess")
    df.foreachPartition { rows =>
      copyPartition(rows, options, schema, tempTable, Some(accumulator))
    }

    // Stage 3. if the accumulator value is not equal to the [[Dataframe]] instance's partition
    // number. The Spark job will fail with a [[PartitionCopyFailureException]].
    // Otherwise, we will run a rename table ddl statement to rename the tmp table to the final
    // target table.
    val partNum = df.rdd.getNumPartitions
    val conn2 = JdbcUtils.createConnectionFactory(options)()
    try {
      if (accumulator.value == partNum) {
        if (tableExists(conn2, options.table)) {
          JdbcUtils.dropTable(conn2, options.table)
        }

        val newTableName = s"${options.table}".split("\\.").last
        val renameTempTbl = s"ALTER TABLE $tempTable RENAME TO $newTableName"
        executeStatement(conn2, renameTempTbl)
      } else {
        throw new PartitionCopyFailureException(
          s"""
             | Job aborted for that there are some partitions failed to copy data to greenPlum:
             | Total partitions is: $partNum and successful partitions is: ${accumulator.value}.
             | You can retry again.
            """.stripMargin)
      }
    } finally {
      if (tableExists(conn2, tempTable)) {
        retryingDropTableSilent(conn2, tempTable)
      }
      closeConnSilent(conn2)
    }
  }

  /**
   * Drop the table and retry automatically when exception occurred.
   */
  def retryingDropTableSilent(conn: Connection, table: String): Unit = {
    val dropTmpTableMaxRetry = 3
    var dropTempTableRetryCount = 0
    var dropSuccess = false

    while (!dropSuccess && dropTempTableRetryCount < dropTmpTableMaxRetry) {
      try {
        JdbcUtils.dropTable(conn, table)
        dropSuccess = true
      } catch {
        case e: Exception =>
          dropTempTableRetryCount += 1
          logWarning(s"Drop tempTable $table failed for $dropTempTableRetryCount" +
            s"/${dropTmpTableMaxRetry} times, and will retry.", e)
      }
    }
    if (!dropSuccess) {
      logError(s"Drop tempTable $table failed for $dropTmpTableMaxRetry times," +
        s" and will not retry.")
    }
  }

  /**
   * https://www.postgresql.org/docs/9.2/sql-copy.html
   *
   * Copy data to greenplum in these cases, which need update origin gptable.
   * 1. Overwrite an existed gptable, which is a CascadingTruncateTable.
   * 2. Append data to a gptable.
   *
   * When transcationOn option is true, we will coalesce the dataFrame to one partition,
   * and the copy operation for each partition is atomic.
   *
   * @param df the [[DataFrame]] will be copy to the Greenplum
   * @param schema the table schema in Greemnplum
   * @param options Options for the Greenplum data source
   */
  def nonTransactionalCopy(
      df: DataFrame,
      schema: StructType,
      options: GreenplumOptions): Unit = {
    df.foreachPartition { rows =>
      copyPartition(rows, options, schema, options.table)
    }
  }

  /**
   * Copy a partition's data to a gptable.
   *
   * @param rows rows of a partition will be copy to the Greenplum
   * @param options Options for the Greenplum data source
   * @param schema the table schema in Greemnplum
   * @param tableName the tableName, to which the data will be copy
   * @param accumulator account for recording the successful partition num
   */
  def copyPartition(
      rows: Iterator[Row],
      options: GreenplumOptions,
      schema: StructType,
      tableName: String,
      accumulator: Option[LongAccumulator] = None): Unit = {
    val valueConverters: Array[(Row, Int) => String] =
      schema.map(s => makeConverter(s.dataType)).toArray
    val tmpDir = Utils.createTempDir(Utils.getLocalDir(SparkEnv.get.conf), "greenplum")
    val dataFile = new File(tmpDir, UUID.randomUUID().toString)
    logInfo(s"Start to write data to local tmp file: ${dataFile.getCanonicalPath}")
    val out = new BufferedOutputStream(new FileOutputStream(dataFile))
    val startW = System.nanoTime()
    try {
      rows.foreach(r => out.write(
        convertRow(r, schema.length, options.delimiter, valueConverters)))
    } finally {
      out.close()
    }
    val endW = System.nanoTime()
    logInfo(s"Finished writing data to local tmp file: ${dataFile.getCanonicalPath}, " +
      s"time taken: ${(endW - startW) / math.pow(10, 9)}s")
    val in = new BufferedInputStream(new FileInputStream(dataFile))
    val sql = s"COPY $tableName" +
      s" FROM STDIN WITH NULL AS 'NULL' DELIMITER AS E'${options.delimiter}'"

    val promisedCopyNums = Promise[Long]
    val conn = JdbcUtils.createConnectionFactory(options)()
    val copyManager = new CopyManager(conn.asInstanceOf[BaseConnection])
    val copyThread = new Thread("copy-to-gp-thread") {
      override def run(): Unit = promisedCopyNums.complete(Try(copyManager.copyIn(sql, in)))
    }

    try {
      logInfo(s"Start copy steam to Greenplum with copy command $sql")
      val start = System.nanoTime()
      copyThread.start()
      try {
        val nums = ThreadUtils.awaitResult(promisedCopyNums.future,
          Duration(options.copyTimeout, TimeUnit.MILLISECONDS))
        val end = System.nanoTime()
        logInfo(s"Copied $nums row(s) to Greenplum," +
          s" time taken: ${(end - start) / math.pow(10, 9)}s")
      } catch {
        case _: TimeoutException =>
          throw new TimeoutException(
            s"""
               | The copy operation for copying this partition's data to greenplum has been running for
               | more than the timeout: ${TimeUnit.MILLISECONDS.toSeconds(options.copyTimeout)}s.
               | You can configure this timeout with option copyTimeout, such as "2h", "100min",
               | and default copyTimeout is "1h".
               """.stripMargin)
      }
      accumulator.foreach(_.add(1L))
    } finally {
      copyThread.interrupt()
      copyThread.join()
      in.close()
      closeConnSilent(conn)
    }
  }

  def closeConnSilent(conn: Connection): Unit = {
    try {
      conn.close()
    } catch {
      case e: Exception => logWarning("Exception occured when closing connection.", e)
    }
  }

  def executeStatement(conn: Connection, sql: String): Unit = {
    val statement = conn.createStatement()
    try {
      statement.executeUpdate(sql)
    } finally {
      statement.close()
    }
  }

  def reorderDataFrameColumns(df: DataFrame, tableSchema: Option[StructType]): DataFrame = {
    tableSchema.map { schema =>
      df.selectExpr(schema.map(filed => filed.name): _*)
    }.getOrElse(df)
  }

  /**
   * Returns true if the table already exists in the JDBC database.
   */
  def tableExists(conn: Connection, table: String): Boolean = {
    val query = s"SELECT * FROM $table WHERE 1=0"
    Try {
      val statement = conn.prepareStatement(query)
      try {
        statement.executeQuery()
      } finally {
        statement.close()
      }
    }.isSuccess
  }
}

private[greenplum] case class CanonicalTblName(schema: Option[String], rawName: String)

/**
 * Extract schema name and raw table name from a table name string.
 */
private[greenplum] object TableNameExtractor {
  private val nonSchemaTable = """[\"]*([0-9a-zA-Z_]+)[\"]*""".r
  private val schemaTable = """([\"]*[0-9a-zA-Z_]+[\"]*)\.[\"]*([0-9a-zA-Z_]+)[\"]*""".r

  def extract(tableName: String): CanonicalTblName = {
    tableName match {
      case nonSchemaTable(t) => CanonicalTblName(None, t)
      case schemaTable(schema, t) => CanonicalTblName(Some(schema), t)
      case _ => throw new IllegalArgumentException(
        s"""
           | The table name is illegal, you can set it with the dbtable option, such as
           | "schemaname"."tableName" or just "tableName" with a default schema "public".
         """.stripMargin
      )
    }
  }
}
