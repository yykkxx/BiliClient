package com.RobinNotBad.BiliClient.model

enum class ContentType(private val typeCode: Int) {
    Video(1),
    Topic(2),
    Activity(4),
    ShortVideo(5),
    BlackRoom(6),
    Announcement(7),
    Live(8),
    ActivityContent(9),
    LiveAnnouncement(10),
    Photo(11),
    Article(12),
    Ticket(13),
    Audio(14),
    Judgement(15),
    Review(16),
    Dynamic(17),
    VideoPlaylist(18),
    AudioPlaylist(19),
    Manga_1(20),
    Manga_2(21),
    Manga_3(22),
    Opus(23),
    Course(33)
    ;

    fun getTypeCode(): Int = typeCode

    companion object {
        // 原 Java 方法声明 throws TerminalIllegalTypeCodeException。
        // 同批次 Java 调用方（ReplyInfoActivity）仍在 catch 该受检异常，
        // 若不保留声明的 throws，javac 会报 "exception is never thrown in body of
        // corresponding try statement"。故此处保留 @Throws 以保证 Java 端可编译。
        @JvmStatic
        @Throws(TerminalIllegalTypeCodeException::class)
        fun getContentType(typeCode: Int): ContentType {
            for (contentType in ContentType.values()) {
                if (contentType.getTypeCode() == typeCode) {
                    return contentType
                }
            }
            throw TerminalIllegalTypeCodeException()
        }
    }

    class TerminalIllegalTypeCodeException : Exception()
}
