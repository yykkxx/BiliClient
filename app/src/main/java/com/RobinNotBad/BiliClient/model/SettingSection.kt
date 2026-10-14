package com.RobinNotBad.BiliClient.model

class SettingSection {
    @JvmField val type: String?
    @JvmField val id: String?
    @JvmField val name: String?
    @JvmField val desc: String?
    @JvmField val defaultValue: String?
    @JvmField var extra: Any? = null

    constructor(type: String?, name: String?, key: String?, desc: String?, defaultValue: String?) {
        this.type = type
        this.id = key
        this.name = name
        this.desc = desc
        this.defaultValue = defaultValue
    }

    constructor(type: String?, name: String?, key: String?, desc: String?, defaultValue: String?, extra: Any?) {
        this.type = type
        this.id = key
        this.name = name
        this.desc = desc
        this.defaultValue = defaultValue
        this.extra = extra
    }
}
