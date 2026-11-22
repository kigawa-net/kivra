package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplitProposalRulesTest {

    @Test
    fun onlyAnOpenProposalCanBeDecided() {
        assertTrue(canDecideSplitProposal("PROPOSED"))
        assertFalse(canDecideSplitProposal("APPROVED"))
        assertFalse(canDecideSplitProposal("REJECTED"))
        assertFalse(canDecideSplitProposal("EXPIRED"))
        assertFalse(canDecideSplitProposal("bogus"))
    }

    @Test
    fun stateLabelsAreJapaneseAndAnUnknownStateIsNamedAsUnknown() {
        assertEquals("未決定", splitProposalStateText("PROPOSED"))
        assertEquals("却下", splitProposalStateText("REJECTED"))
        assertEquals("失効", splitProposalStateText("EXPIRED"))
        assertEquals("不明", splitProposalStateText("SOMETHING_NEW"))
    }

    @Test
    fun decisionRefusalsNameTheirCause() {
        assertEquals(
            "この提案は既に承認されています。一覧を読み直してください。",
            operatorWriteFailureText(OperatorAction.APPROVE_SPLIT, WriteFailure.STALE, "PROPOSAL_APPROVED"),
        )
        assertEquals(
            "この提案は既に却下されています。",
            operatorWriteFailureText(OperatorAction.APPROVE_SPLIT, WriteFailure.STALE, "PROPOSAL_REJECTED"),
        )
        assertEquals(
            "親の子 order の数が上限に達しています。",
            operatorWriteFailureText(OperatorAction.APPROVE_SPLIT, WriteFailure.STALE, "CHILD_LIMIT"),
        )
        assertEquals(
            "order の木の大きさが上限に達しています。",
            operatorWriteFailureText(OperatorAction.APPROVE_SPLIT, WriteFailure.STALE, "TREE_LIMIT"),
        )
        // A 409 with no code, or an unknown one, is the shared conflict text; the server's error body is never shown.
        assertEquals(
            writeFailureText(WriteFailure.STALE),
            operatorWriteFailureText(OperatorAction.REJECT_SPLIT, WriteFailure.STALE, "NO_SUCH_CODE"),
        )
        assertEquals(writeFailureText(WriteFailure.FORBIDDEN), operatorWriteFailureText(OperatorAction.REJECT_SPLIT, WriteFailure.FORBIDDEN))
    }
}
