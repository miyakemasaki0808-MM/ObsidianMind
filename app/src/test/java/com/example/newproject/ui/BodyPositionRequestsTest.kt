package com.example.newproject.ui

import com.example.newproject.ui.screen.BodyPositionRequest
import com.example.newproject.ui.screen.BodyPositionRequests
import com.example.newproject.ui.screen.BodyPositionSource
import org.junit.Assert.assertEquals
import org.junit.Test

// 本文の位置の口の書き込みの番号（→ features/note_spread.md §5「位置の書き込み」）。
class BodyPositionRequestsTest {

    /** **同じ値を書いても番号は進む。** 位置の値だけを見ると、同じブロックへの外からの送りを見開きが見分けられない。 */
    @Test
    fun `同じ値を書いても番号は進み、出どころが残る`() {
        val requests = BodyPositionRequests(initialBlock = 3)
        assertEquals(BodyPositionRequest(0, 3, BodyPositionSource.External), requests.last)

        requests.record(0, BodyPositionSource.External)
        requests.record(0, BodyPositionSource.External)
        assertEquals(BodyPositionRequest(2, 0, BodyPositionSource.External), requests.last)

        requests.record(5, BodyPositionSource.SpreadTurn)
        assertEquals(BodyPositionRequest(3, 5, BodyPositionSource.SpreadTurn), requests.last)
    }
}
