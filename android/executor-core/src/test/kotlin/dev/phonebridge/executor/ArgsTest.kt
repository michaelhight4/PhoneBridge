package dev.phonebridge.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgsTest {

    @Test
    fun `numbers are normalised from json shapes`() {
        val args = Args("int" to 3, "long" to 4L, "double" to 5.0, "string" to "6", "exponent" to 7e3)
        assertEquals(3, args.int("int"))
        assertEquals(4L, args.long("long"))
        assertEquals(5, args.int("double"))
        assertEquals(6, args.int("string"))
        assertEquals(7000L, args.long("exponent"))
    }

    @Test
    fun `fractional numbers are rejected as integers`() {
        val args = Args("x" to 1.5)
        assertFailsWith<BadArgsException> { args.int("x") }
    }

    @Test
    fun `missing values are null or bad_args depending on the accessor`() {
        val args = Args()
        assertNull(args.int("x"))
        assertNull(args.string("label"))
        assertEquals(42, args.intOr("x", 42))
        assertFailsWith<BadArgsException> { args.requiredInt("x") }
        assertFailsWith<BadArgsException> { args.requiredString("x") }
        assertFalse(args.has("x"))
    }

    @Test
    fun `empty required string is rejected`() {
        val args = Args("name" to "")
        assertFailsWith<BadArgsException> { args.requiredString("name") }
        assertEquals("", args.string("name"))
    }

    @Test
    fun `wrong types are bad_args`() {
        val args = Args("x" to mapOf("nested" to true))
        assertFailsWith<BadArgsException> { args.int("x") }
        val stringArgs = Args("label" to 12)
        assertFailsWith<BadArgsException> { stringArgs.string("label") }
    }

    @Test
    fun `booleans accept json and strings`() {
        val args = Args("a" to true, "b" to "false", "c" to "YES")
        assertEquals(true, args.boolean("a"))
        assertEquals(false, args.boolean("b"))
        assertEquals(true, args.boolean("c"))
        assertEquals(false, args.booleanOr("missing", false))
        assertFailsWith<BadArgsException> { Args("a" to "maybe").boolean("a") }
    }

    @Test
    fun `alternate key names are found in order`() {
        val args = Args("nodeId" to "n4")
        assertEquals("n4", args.stringAny("node_id", "nodeId"))
        assertNull(Args().stringAny("node_id", "nodeId"))
        assertTrue(Args("node_id" to "n1").stringAny("node_id", "nodeId") == "n1")
    }

    @Test
    fun `bad args exception names the field`() {
        val error = assertFailsWith<BadArgsException> { Args().requiredInt("x") }
        assertEquals("x", error.field)
        assertTrue(error.message!!.startsWith("bad_args: x:"))
    }
}
