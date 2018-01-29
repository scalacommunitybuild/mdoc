package vork.markdown.processors

import scala.meta.testkit.DiffAssertions
import org.scalatest.FunSuite
import vork.runtime.DocumentBuilder
import vork.runtime.Macros

class RuntimeSuite extends FunSuite with DiffAssertions with DocumentBuilder {

  test("app") {
    val document = build()
    val obtained = pprint.PPrinter.BlackWhite.apply(document, height = 1000, width = 70)
//    println(obtained)
  }

  override def app(): Unit = {
    // format: off
    val x = List(1)                                                             ; binder(x); statement {
    val y = x.map(_ + 1)                                                        ; binder(y); statement {
    val x = y.map(_ + 1)                                                        ; binder(x)
    println(s"x has length " + x.length)                                        ; statement {
    case class User(name: String, age: Int)                                     ; /* defined class User */ section {
    val y = x.map(i => User("John", i))                                         ; binder(y)
    System.err.println(s"y users are " + y)                                     ; statement {
    case class User(age: Int, name: String)                                     ; /* defined class User */ statement {
    val y = x.map(i => User(i, "John"))                                         ; binder(y); statement {
    val user @ User(x, y) = User(42, "Susan")                                   ; binder(user); binder(x); binder(y); statement { section {
    val compiled = Macros.fail("val z: String = 42")                            ; binder(compiled); statement { section { () } } } } } } } } } } }
  }
}

