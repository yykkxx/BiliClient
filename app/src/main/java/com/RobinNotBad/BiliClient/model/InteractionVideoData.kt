package com.RobinNotBad.BiliClient.model

class InteractionVideoData {
    @JvmField var title: String? = null
    @JvmField var edgeId: Long = 0L
    @JvmField var storyList: MutableList<InteractionStoryNode>? = null
    @JvmField var edges: InteractionEdge? = null
    @JvmField var preload: InteractionPreload? = null
    @JvmField var hiddenVars: MutableList<InteractionHiddenVar>? = null
    @JvmField var isLeaf: Int = 0
    @JvmField var noTutorial: Int = 0
    @JvmField var noBacktracking: Int = 0
    @JvmField var noEvaluation: Int = 0

    class InteractionStoryNode {
        @JvmField var nodeId: Long = 0L
        @JvmField var edgeId: Long = 0L
        @JvmField var title: String? = null
        @JvmField var cid: Long = 0L
        @JvmField var startPos: Long = 0L
        @JvmField var cover: String? = null
        @JvmField var isCurrent: Int = 0
        @JvmField var cursor: Long = 0L
    }

    class InteractionEdge {
        @JvmField var dimension: InteractionDimension? = null
        @JvmField var questions: MutableList<InteractionQuestion>? = null
        @JvmField var skin: InteractionSkin? = null
    }

    class InteractionDimension {
        @JvmField var width: Int = 0
        @JvmField var height: Int = 0
        @JvmField var rotate: Int = 0
        @JvmField var sar: String? = null
    }

    class InteractionQuestion {
        @JvmField var id: Long = 0L
        @JvmField var type: Int = 0
        @JvmField var startTimeR: Long = 0L
        @JvmField var duration: Long = 0L
        @JvmField var pauseVideo: Int = 0
        @JvmField var title: String? = null
        @JvmField var choices: MutableList<InteractionChoice>? = null
        @JvmField var fadeInTime: Long = 0L
        @JvmField var fadeOutTime: Long = 0L
    }

    class InteractionChoice {
        @JvmField var id: Long = 0L
        @JvmField var platformAction: String? = null
        @JvmField var nativeAction: String? = null
        @JvmField var condition: String? = null
        @JvmField var cid: Long = 0L
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
        @JvmField var textAlign: Int = 0
        @JvmField var option: String? = null
        @JvmField var selected: InteractionAnimation? = null
        @JvmField var submited: InteractionAnimation? = null
        @JvmField var isDefault: Int = 0
        @JvmField var isHidden: Int = 0
    }

    class InteractionAnimation {
        @JvmField var type: String? = null
        @JvmField var duration: Long = 0L
    }

    class InteractionSkin {
        @JvmField var choiceImage: String? = null
        @JvmField var titleTextColor: String? = null
        @JvmField var titleShadowColor: String? = null
        @JvmField var titleShadowOffsetX: Int = 0
        @JvmField var titleShadowOffsetY: Int = 0
        @JvmField var titleShadowRadius: Int = 0
        @JvmField var progressbarColor: String? = null
        @JvmField var progressbarShadowColor: String? = null
    }

    class InteractionPreload {
        @JvmField var video: MutableList<InteractionPreloadVideo>? = null
    }

    class InteractionPreloadVideo {
        @JvmField var aid: Long = 0L
        @JvmField var cid: Long = 0L
    }

    class InteractionHiddenVar {
        @JvmField var value: Long = 0L
        @JvmField var id: String? = null
        @JvmField var idV2: String? = null
        @JvmField var type: Int = 0
        @JvmField var isShow: Int = 0
        @JvmField var name: String? = null
        @JvmField var skipOverwrite: Int = 0
    }
}
