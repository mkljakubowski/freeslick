package freeslick

import java.sql.{ Types, ResultSet }
import java.util.UUID

import freeslick.profile.utils._
import slick.ast._
import slick.compiler.{ CompilerState, QueryCompiler }
import slick.dbio.DBIO
import slick.driver._
import slick.jdbc.meta.MTable
import slick.jdbc.{ JdbcType, ResultSetConcurrency, ResultSetType }
import slick.profile.{ Capability, RelationalProfile }
import slick.util.MacroSupport.macroSupportInterpolation

import scala.concurrent.ExecutionContext

/**
 * Slick profile for DB2.
 *
 * This profile implements the `scala.slick.driver.JdbcProfile`
 * ''without'' the following capabilities:
 *
 *    - JdbcProfile.capabilities.returnInsertKey
 *    - JdbcProfile.capabilities.booleanMetaData
 *    - RelationalProfile.capabilities.reverse
 *    - JdbcProfile.capabilities.supportsByte
 *
 */
trait DB2Profile extends JdbcDriver with UniqueConstraintIndexesBuilder with DriverRowNumberPagination
    with FreeslickSequenceDDLBuilder with TableSpaceConfig {
  driver =>

  override protected def computeCapabilities: Set[Capability] = (super.computeCapabilities
    - JdbcProfile.capabilities.booleanMetaData
    - RelationalProfile.capabilities.reverse
    - JdbcProfile.capabilities.supportsByte
  )

  // "merge into" (i.e. server side upsert) won't return generated keys in db2 jdbc
  // this will do a select then insert or update in a transaction. The insert will
  // return generated keys
  override protected lazy val useServerSideUpsertReturning = false

  override protected def computeQueryCompiler =
    super.computeQueryCompiler.addAfter(new FreeslickRewriteBooleans, QueryCompiler.sqlPhases.last)

  override val columnTypes = new JdbcTypes

  override def createQueryBuilder(n: Node, state: CompilerState): QueryBuilder = new QueryBuilder(n, state)

  override def createColumnDDLBuilder(column: FieldSymbol, table: Table[_]): ColumnDDLBuilder = new ColumnDDLBuilder(column)

  override def createTableDDLBuilder(table: Table[_]): TableDDLBuilder = new TableDDLBuilder(table)

  override def createSequenceDDLBuilder(seq: Sequence[_]): SequenceDDLBuilder[_] = new SequenceDDLBuilder(seq)

  override def defaultTables(implicit ec: ExecutionContext): DBIO[Seq[MTable]] = {
    import driver.api._
    val userQ = Functions.user.result
    userQ.flatMap(user => MTable.getTables(None, Some(user), None, Some(Seq("TABLE"))))
  }

  override def defaultSqlTypeName(tmd: JdbcType[_], sym: Option[FieldSymbol]): String = tmd.sqlType match {
    case java.sql.Types.TINYINT => "SMALLINT"
    case _ => super.defaultSqlTypeName(tmd, sym)
  }

  override val scalarFrom: Option[String] = Some("SYSIBM.SYSDUMMY1")

  import TypeUtil._
  class QueryBuilder(tree: Node, state: CompilerState) extends super.QueryBuilder(tree, state) with RowNumberPagination {
    override protected val concatOperator = Some("||")
    val db2Pi = pi.substring(0, 32) // DB2 has a max number of digits
    override protected val quotedJdbcFns = Some(Nil)
    override def expr(n: Node, skipParens: Boolean = false): Unit = {
      n match {
        case Library.NextValue(SequenceNode(name)) => b"`$name.nextval"
        case Library.CurrentValue(SequenceNode(name)) => b"`$name.currval"
        case RowNumber(by) =>
          b"row_number() over("
          if (by.isEmpty) b"order by 1"
          else buildOrderByClause(by)
          b")"
        case Library.IfNull(ch, d) => b"coalesce($ch, $d)" //Curious DB2 should have ifnull, tests give "No authorized routine named "ISNULL" of type "FUNCTION" having compatible arguments was found" though
        case Library.Database() => b"current schema"
        case Library.User() => b"current user"
        case Library.CurrentTime() => b"current timestamp"
        case Library.CurrentDate() => b"current date"
        case Library.Pi() => b"$db2Pi"
        case _ => super.expr(n, skipParens)
      }
    }
    override protected def buildSelectPart(n: Node): Unit = n match {
      // DB2 UUID literals seem to need hex applied in the Select part and come back as VARCHAR
      case c @ LiteralNode(uuid) if !c.volatileHint && jdbcTypeFor(c.nodeType) == columnTypes.uuidJdbcType => b"hex(${columnTypes.uuidJdbcType.valueToSQLLiteral(uuid.asInstanceOf[UUID])})"
      case n => super.buildSelectPart(n)
    }
  }

  class ColumnDDLBuilder(column: FieldSymbol) extends super.ColumnDDLBuilder(column) {
    override protected def appendOptions(sb: StringBuilder) {
      if (defaultLiteral ne null) sb append " DEFAULT " append defaultLiteral
      if (autoIncrement) sb append " GENERATED BY DEFAULT AS IDENTITY(START WITH 1)"
      if (notNull) sb append " NOT NULL"
      if (primaryKey) sb append " PRIMARY KEY"
      if (jdbcType == columnTypes.booleanJdbcType)
        sb append " check (" append quoteIdentifier(column.name) append " in (1, 0))"
    }
  }

  class SequenceDDLBuilder[T](seq: Sequence[T]) extends super.SequenceDDLBuilder(seq) {
    override def buildDDL: DDL = buildSeqDDL(seq)
  }

  class TableDDLBuilder(table: Table[_]) extends super.TableDDLBuilder(table) with UniqueConstraintIndexes {
    override protected def createPhase1 = Iterable(createTable) ++ primaryKeys.map(createPrimaryKey) ++ indexes.flatMap(createIndexStmts)

    override def createTable: String = {
      super.createTable +
        tableTableSpace.map(t => s" in $t").getOrElse("") +
        indexTableSpace.map(t => s" index in $t").getOrElse("")
    }
  }
  class JdbcTypes extends super.JdbcTypes {
    override val booleanJdbcType = new BooleanJdbcType
    /* DB2 does not have a proper BOOLEAN type. The suggested workaround is
       * SMALLINT with constants 1 and 0 for TRUE and FALSE. */
    class BooleanJdbcType extends super.BooleanJdbcType {
      override def sqlType = java.sql.Types.SMALLINT //ColumnDDLBuilder adds constraints to only be '1' or '0'
      override def valueToSQLLiteral(value: Boolean) = if (value) "1" else "0"
    }
    override val uuidJdbcType = new UUIDJdbcType {
      override def sqlType = java.sql.Types.VARBINARY
      override def getValue(r: ResultSet, idx: Int) = {
        if (r.getMetaData.getColumnType(idx) == Types.VARCHAR) {
          // literal uuids in select part come as VARCHAR, this is odd and needs a special case
          UUIDParser.dashlessStringToUUID(r.getString(idx))
        } else {
          fromBytes(r.getBytes(idx))
        }
      }
      override def sqlTypeName(sym: Option[FieldSymbol]) = "CHAR(16) FOR BIT DATA"
      override def hasLiteralForm = true
      override def valueToSQLLiteral(value: UUID): String =
        "x'" + value.toString.replace("-", "") + "'"
    }
  }
}

object DB2Profile extends DB2Profile
