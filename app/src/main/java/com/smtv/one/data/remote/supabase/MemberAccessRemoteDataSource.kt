package com.smtv.one.data.remote.supabase

import com.smtv.one.domain.model.CosmeticEntitlement
import com.smtv.one.domain.model.CosmeticEntitlements
import com.smtv.one.domain.model.MemberAccess
import com.smtv.one.domain.model.MemberTier
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemberAccessRemoteDataSource @Inject constructor(
    private val postgrest: Postgrest
) {
    suspend fun getMemberAccess(): MemberAccess {
        val remote = postgrest.rpc("get_my_member_access")
            .decodeList<MemberAccessResponse>()
            .firstOrNull()
            ?: return MemberAccess.None
        val tier = MemberTier.entries.firstOrNull { it.name == remote.tier }
            ?: return MemberAccess.None
        val entitlements = remote.entitlements
            .mapNotNull { value -> CosmeticEntitlement.entries.firstOrNull { it.name == value } }
            .toSet()

        return MemberAccess(
            tier = tier,
            entitlements = CosmeticEntitlements(entitlements)
        )
    }
}

@Serializable
private data class MemberAccessResponse(
    val tier: String,
    val entitlements: List<String>
)
