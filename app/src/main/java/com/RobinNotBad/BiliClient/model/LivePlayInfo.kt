package com.RobinNotBad.BiliClient.model

import java.io.Serializable

class LivePlayInfo : Serializable {
    @JvmField var roomid: Long = 0
    @JvmField var short_id: Long = 0
    @JvmField var uid: Long = 0
    @JvmField var isHidden: Boolean = false
    @JvmField var isLocked: Boolean = false
    @JvmField var isPortrait: Boolean = false
    @JvmField var live_status: Int = 0
    @JvmField var encrypted: Boolean = false
    @JvmField var pwd_verified: Boolean = false
    @JvmField var live_time: Long = 0
    @JvmField var conf_json: String? = null
    @JvmField var playUrl: PlayUrl? = null
    @JvmField var official_type: Int = 0
    @JvmField var official_room_id: Int = 0
    @JvmField var risk_with_delay: Int = 0

    class PlayUrl : Serializable {
        @JvmField var cid: Long = 0
        @JvmField var g_qn_desc: MutableList<QnDesc>? = null
        @JvmField var stream: MutableList<ProtocolInfo>? = null
        @JvmField var p2p_data: P2PData? = null
        @JvmField var dolby_qn: Int = 0
    }

    class P2PData : Serializable {
        @JvmField var p2p: Boolean = false
        @JvmField var p2p_type: Int = 0
        @JvmField var m_p2p: Boolean = false
        @JvmField var m_servers: MutableList<String>? = null
    }

    class QnDesc : Serializable {
        @JvmField var qn: Int = 0
        @JvmField var desc: String? = null
        @JvmField var hdr_desc: String? = null
        @JvmField var attr_desc: String? = null
    }

    class ProtocolInfo : Serializable {
        @JvmField var protocol_name: String? = null
        @JvmField var format: MutableList<Format>? = null
    }

    class Format : Serializable {
        @JvmField var format_name: String? = null
        @JvmField var codec: MutableList<Codec>? = null
        @JvmField var master_url: String? = null
    }

    class Codec : Serializable {
        @JvmField var codec_name: String? = null
        @JvmField var current_qn: Int = 0
        @JvmField var accept_qn: MutableList<Int>? = null
        @JvmField var base_url: String? = null
        @JvmField var url_info: MutableList<UrlInfo>? = null
        @JvmField var hdr_qn: Int = 0
        @JvmField var dolby_type: Int = 0
        @JvmField var attr_name: String? = null
    }

    class UrlInfo : Serializable {
        @JvmField var host: String? = null
        @JvmField var extra: String? = null
        @JvmField var stream_ttl: Int = 0
    }
}
