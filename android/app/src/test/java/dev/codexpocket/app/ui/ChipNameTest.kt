package dev.codexpocket.app.ui

import org.junit.Assert.*
import org.junit.Test

class ChipNameTest {
    @Test fun shortNamesAreUntouched() {
        assertEquals("shot.png", chipName("shot.png"))
    }

    @Test fun longNamesKeepExtensionAndStayDistinct() {
        val docx = chipName("计算机组成原理实验报告_运算器设计与仿真_最终版.docx")
        val pdf = chipName("计算机组成原理实验报告_运算器设计与仿真_最终版.pdf")
        assertTrue(docx, docx.endsWith(".docx") && docx.contains('…'))
        assertTrue(pdf, pdf.endsWith(".pdf") && pdf.startsWith("计算机"))
        assertNotEquals(docx, pdf)
    }
}
