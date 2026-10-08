package com.smtv.one.core.profile

interface ProfileScopedCredentialStore {
    fun removeProfile(profileId: Int)
    fun clearAllProfiles()
}
