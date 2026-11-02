package org.pararead.android

import org.junit.Assert.*
import org.junit.Test

class ReaderNoticeTest {
    @Test fun cancelledWholeJobIsBrief() {
        assertEquals(2500L, readerNoticeDuration("全篇已取消，点击全篇可继续：1 / 6 页"))
    }
    @Test fun progressAndErrorsRemainVisible() {
        listOf("全篇翻译：1 / 6 页", "全篇已中断，点击全篇可重试", "操作失败，进度未确认保存", "").forEach {
            assertNull(readerNoticeDuration(it))
        }
    }
}
