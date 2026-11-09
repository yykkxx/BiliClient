package com.RobinNotBad.BiliClient.api
/** Only signing identity lookup is stubbed; renewal APIs and all account stores are production files. */
object AppRcmdApi {
    const val IDENTITY_PHONE = "phone"
    const val IDENTITY_HD = "hd"
    const val IDENTITY_TV = "tv"
    class RcmdIdentity(val id: String, val feedAppKey: String, val feedAppSec: String, val ua: String)
    fun getIdentityById(id: String): RcmdIdentity? =
        if (id in listOf(IDENTITY_PHONE, IDENTITY_HD, IDENTITY_TV)) RcmdIdentity(id, "fixture-key", "fixture-secret", "fixture-ua") else null
}
