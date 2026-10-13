package com.niuma.core

import org.junit.Assert.*
import org.junit.Test

class PunchVisualPolicyTest {
    private val width = 1000
    private val height = 2000
    private val label = VisualText("下班打卡", VisualBox(390, 1060, 610, 1130))
    private val date = VisualText("10月12日 周一", VisualBox(420, 1150, 580, 1200))

    @Test fun blueCircleWithMatchingShiftAndDateCanBeTapped() {
        val target = PunchVisualPolicy.blueButton(Kind.OUT, 10, 12, width, height,
            listOf(label, date, VisualText("打卡", VisualBox(0, 0, 100, 50)))) { x, y ->
            if ((x - 500) * (x - 500) + (y - 1140) * (y - 1140) < 190 * 190)
                0xFF087BFF.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals(500, target?.x)
        assertEquals(1095, target?.y)
    }

    @Test fun grayOrTinyBlueCircleCannotBeTapped() {
        val gray = PunchVisualPolicy.blueButton(Kind.OUT, 10, 12, width, height,
            listOf(label, date)) { _, _ -> 0xFF888888.toInt() }
        assertNull(gray)
        val tiny = PunchVisualPolicy.blueButton(Kind.OUT, 10, 12, width, height,
            listOf(label, date)) { x, y ->
            if ((x - 500) * (x - 500) + (y - 1095) * (y - 1095) < 30 * 30)
                0xFF087BFF.toInt() else 0xFFFFFFFF.toInt()
        }
        assertNull(tiny)
        assertNull(PunchVisualPolicy.blueButton(Kind.OUT, 10, 12, width, height,
            listOf(label, date)) { _, _ -> 0xFF087BFF.toInt() })
    }

    @Test fun wrongShiftOrDateCannotBeTapped() {
        val blue: (Int, Int) -> Int = { _, _ -> 0xFF087BFF.toInt() }
        assertNull(PunchVisualPolicy.blueButton(Kind.IN, 10, 12, width, height, listOf(label, date), blue))
        assertNull(PunchVisualPolicy.blueButton(Kind.OUT, 10, 13, width, height, listOf(label, date), blue))
    }

    @Test fun singleButtonChoosesVisibleShiftAndRejectsTwoBlueButtons() {
        val labels = listOf(
            VisualText("上班打卡", VisualBox(190, 1060, 410, 1130)),
            VisualText("下班打卡", VisualBox(590, 1060, 810, 1130)), date)
        val oneBlue: (Int, Int) -> Int = { x, y ->
            if ((x - 300) * (x - 300) + (y - 1140) * (y - 1140) < 190 * 190)
                0xFF087BFF.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals(VisualPunchTarget(Kind.IN, VisualPoint(300, 1095)),
            PunchVisualPolicy.singleBlueButton(10, 12, width, height, labels, oneBlue))
        val twoBlue: (Int, Int) -> Int = { x, y ->
            if (listOf(300, 700).any { center ->
                    (x - center) * (x - center) + (y - 1140) * (y - 1140) < 190 * 190
                }) 0xFF087BFF.toInt() else 0xFFFFFFFF.toInt()
        }
        assertNull(PunchVisualPolicy.singleBlueButton(10, 12, width, height, labels, twoBlue))
    }

    @Test fun resultLinkMustBeBelowMatchingSuccessTitle() {
        val lines = listOf(
            VisualText("查看打卡结果", VisualBox(300, 200, 700, 260)),
            VisualText("下班打卡成功", VisualBox(320, 1450, 680, 1510)),
            VisualText("打卡时间 2026-10-12 21:55", VisualBox(250, 1580, 750, 1630)),
            VisualText("查看打卡结果", VisualBox(330, 1700, 670, 1770))
        )
        assertEquals(VisualPoint(500, 1735), PunchVisualPolicy.resultLink(Kind.OUT, width, height, lines))
        assertNull(PunchVisualPolicy.resultLink(Kind.IN, width, height, lines))
    }

    @Test fun resultLinkHandlesOcrFragmentsAndCheckmarkWithinSuccessModal() {
        val lines = listOf(
            VisualText("✓ 下班打卡", VisualBox(295, 1440, 495, 1500)),
            VisualText("成功", VisualBox(500, 1442, 590, 1502)),
            VisualText("查看打卡", VisualBox(310, 1700, 485, 1770)),
            VisualText("结果", VisualBox(490, 1703, 570, 1772))
        )
        assertEquals(VisualPoint(440, 1736), PunchVisualPolicy.resultLink(Kind.OUT, width, height, lines))
        assertNull(PunchVisualPolicy.resultLink(Kind.IN, width, height, lines))
    }

    @Test fun resultLinkFollowsSmallerDialogInsteadOfFixedScreenHeight() {
        val lines = listOf(
            VisualText("下班打卡成功", VisualBox(320, 80, 680, 140)), // heads-up notification
            VisualText("下班打卡成功", VisualBox(320, 900, 680, 960)),
            VisualText("打卡时间 2026-10-12 23:59", VisualBox(240, 1040, 760, 1100)),
            VisualText("查看打卡结果", VisualBox(340, 1200, 660, 1260))
        )
        assertEquals(VisualPoint(500, 1230), PunchVisualPolicy.resultLink(Kind.OUT, width, height, lines))
    }

    @Test fun resultLinkDoesNotTapGapBetweenTwoDialogButtons() {
        val lines = listOf(
            VisualText("下班打卡成功", VisualBox(300, 1800, 700, 1860)),
            VisualText("查看打卡结果", VisualBox(175, 2040, 455, 2110)),
            VisualText("立即了解", VisualBox(650, 2040, 865, 2110))
        )
        assertEquals(VisualPoint(315, 2075), PunchVisualPolicy.resultLink(Kind.OUT, 1080, 2340, lines))
    }
}
