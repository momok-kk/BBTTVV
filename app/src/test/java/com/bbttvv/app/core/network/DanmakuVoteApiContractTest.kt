package com.bbttvv.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Field

class DanmakuVoteApiContractTest {
    @Test
    fun `vote and grade forms include the player source fields`() {
        for (name in listOf("submitDanmakuVote", "submitDanmakuGrade")) {
            val method = BilibiliApi::class.java.methods.single { it.name == name }
            val fields = method.parameterAnnotations
                .map { annotations -> annotations.filterIsInstance<Field>().singleOrNull()?.value }
            assertTrue(fields.containsAll(listOf("polaris_app_id", "polaris_platform", "statistics")))
            if (name == "submitDanmakuVote") {
                assertEquals(Int::class.javaPrimitiveType, method.parameterTypes[fields.indexOf("has_self_def")])
            }
        }
        assertEquals(100, DANMAKU_VOTE_POLARIS_APP_ID)
        assertEquals(5, DANMAKU_VOTE_POLARIS_PLATFORM)
        assertEquals("""{"appId":100,"platform":5,"abtest":"","version":""}""", DANMAKU_INTERACTION_STATISTICS)
    }
}
