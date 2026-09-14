package com.example.aiagent.context.branch

data class ConversationBranch(
    val id: Long,
    val name: String,
    val parentBranchId: Long?,
    val checkpointMessageCount: Int,
    val active: Boolean,
)
