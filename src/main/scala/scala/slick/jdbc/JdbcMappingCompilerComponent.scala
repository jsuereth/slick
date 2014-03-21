package scala.slick.jdbc

import java.sql.{PreparedStatement, ResultSet}
import scala.slick.compiler.{InsertCompiler, CompilerState, CodeGen}
import scala.slick.ast._
import scala.slick.relational._
import scala.slick.lifted.MappedProjection
import scala.slick.driver.JdbcDriver
import scala.slick.util.SQLBuilder

/** JDBC driver component which contains the mapping compiler and insert compiler */
trait JdbcMappingCompilerComponent { driver: JdbcDriver =>

  /** A ResultConverterCompiler that builds JDBC-based converters. Instances of
    * this class use mutable state internally. They are meant to be used for a
    * single conversion only and must not be shared or reused. */
  class MappingCompiler extends ResultConverterCompiler[JdbcResultConverterDomain] {
    protected[this] var nextFullIdx = 1
    protected[this] var nextSkippingIdx = 1

    def createColumnConverter(n: Node, path: Node, column: Option[FieldSymbol]): ResultConverter[JdbcResultConverterDomain, _] = {
      val JdbcType(ti, option) = n.nodeType
      val autoInc = column.fold(false)(_.options.contains(ColumnOption.AutoInc))
      val fullIdx = nextFullIdx
      nextFullIdx += 1
      val skippingIdx = if(autoInc) 0 else nextSkippingIdx
      if(!autoInc) nextSkippingIdx += 1
      if(option) SpecializedJdbcResultConverter.option(ti, fullIdx, skippingIdx)
      else SpecializedJdbcResultConverter.base(ti, column.fold(n.toString)(_.name), fullIdx, skippingIdx)
    }

    override def createGetOrElseResultConverter[T](rc: ResultConverter[JdbcResultConverterDomain, Option[T]], default: () => T) = rc match {
      case rc: OptionResultConverter[_] => rc.getOrElse(default)
      case _ => super.createGetOrElseResultConverter[T](rc, default)
    }

    override def createTypeMappingResultConverter(rc: ResultConverter[JdbcResultConverterDomain, Any], mapper: MappedScalaType.Mapper) = {
      val tm = new TypeMappingResultConverter(rc, mapper.toBase, mapper.toMapped)
      mapper.fastPath match {
        case Some(pf) => pf.orElse[Any, Any] { case x => x }.apply(tm).asInstanceOf[ResultConverter[JdbcResultConverterDomain, Any]]
        case None => tm
      }
    }
  }

  /** Code generator phase for JdbcProfile-based drivers. */
  class JdbcCodeGen(f: QueryBuilder => SQLBuilder.Result) extends CodeGen {

    def apply(state: CompilerState): CompilerState = state.map(n => apply(n, state))

    def apply(node: Node, state: CompilerState): Node =
      ClientSideOp.mapResultSetMapping(node, keepType = true) { rsm =>
        val sbr = f(driver.createQueryBuilder(rsm.from, state))
        val nfrom = CompiledStatement(sbr.sql, sbr, rsm.from.nodeType)
        val nmap = (new MappingCompiler).compileMapping(rsm.map)
        rsm.copy(from = nfrom, map = nmap).nodeTyped(rsm.nodeType)
      }
  }

  class JdbcInsertCompiler extends InsertCompiler {
    def createMapping(ins: Insert) = (new MappingCompiler).compileMapping(ins.map)
  }

  class JdbcFastPathExtensionMethods[T, P](val mp: MappedProjection[T, P]) {
    def fastPath(fpf: (TypeMappingResultConverter[JdbcResultConverterDomain, T, _] => JdbcFastPath[T])): MappedProjection[T, P] = mp.genericFastPath {
      case tm @ TypeMappingResultConverter(_: ProductResultConverter[_, _], _, _) =>
        fpf(tm.asInstanceOf[TypeMappingResultConverter[JdbcResultConverterDomain, T, _]])

    }
  }
}

trait JdbcResultConverterDomain extends ResultConverterDomain {
  type Reader = ResultSet
  type Writer = PreparedStatement
  type Updater = ResultSet
}
