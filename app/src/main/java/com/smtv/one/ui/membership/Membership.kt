package com.smtv.one.ui.membership

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.smtv.one.domain.model.MemberAccess
import com.smtv.one.domain.model.MemberTier

val LocalMemberAccess = staticCompositionLocalOf { MemberAccess.None }

object Membership {
    val access: MemberAccess
        @Composable
        @ReadOnlyComposable
        get() = LocalMemberAccess.current

    val tier: MemberTier?
        @Composable
        @ReadOnlyComposable
        get() = access.tier
}
