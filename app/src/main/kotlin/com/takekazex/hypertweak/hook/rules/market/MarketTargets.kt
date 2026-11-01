package com.takekazex.hypertweak.hook.rules.market

import com.takekazex.hypertweak.settings.contract.MarketSettingsKeys as Keys

internal data class MarketTarget(val key: String, val descriptor: String, val value: Any? = false,
    val requestMap: Boolean = false)

internal object MarketTargets {
    private const val DETAIL = "Lcom/xiaomi/market/common/network/retrofit/response/bean/AppDetailV3;->"
    private const val MAIN = "Lcom/xiaomi/market/business_ui/main/MarketTabActivity;->"
    val all = buildList {
        add(MarketTarget(Keys.KEY_MARKET_SPLASH, "Lcom/xiaomi/market/ui/splash/SplashManager;->canShowSplash(Landroid/app/Activity;)Z"))
        // Preserve the host's completion callbacks rather than returning early from the request method.
        add(MarketTarget(Keys.KEY_MARKET_SPLASH, "Lcom/xiaomi/market/ui/splash/DetailSplashAdManager;->canRequestSplashAd(Lcom/xiaomi/market/ui/splash/DetailSplashAdManager\$Env;)Z"))
        add(MarketTarget(Keys.KEY_MARKET_SPLASH, DETAIL + "getShowOpenScreenAd()Z"))
        listOf("needShowAds", "needShowAdsWithSourceFile", "showRecommend", "showTopBanner", "isSourceFileShowAdStyle").forEach {
            add(MarketTarget(Keys.KEY_MARKET_DETAIL, DETAIL + "$it()Z"))
        }
        listOf("isBrowserMarketAdOff", "isBrowserSourceFileAdOff").forEach {
            add(MarketTarget(Keys.KEY_MARKET_DETAIL, DETAIL + "$it()Z", true))
        }
        add(MarketTarget(Keys.KEY_MARKET_VIDEO, DETAIL + "showTopVideo()Z"))
        add(MarketTarget(Keys.KEY_MARKET_SEARCH_SUGGEST, "Lcom/xiaomi/market/business_ui/search/NativeSearchSugFragment;->getRequestParams()Ljava/util/Map;", requestMap = true))
        listOf("tryShowRecommend", "tryShowRecallReCommend").forEach {
            add(MarketTarget(Keys.KEY_MARKET_HOME, MAIN + "$it()V", null))
        }
        add(MarketTarget(Keys.KEY_MARKET_UPDATE, "Lcom/xiaomi/market/ui/UpdateListRvAdapter;->generateRecommendGroupItems(Ljava/util/ArrayList;I)V", null))
        add(MarketTarget(Keys.KEY_MARKET_UPDATE, "Lcom/xiaomi/market/util/SettingsUtils;->shouldShowUpdateAppRelatedRecommend()Z"))
        add(MarketTarget(Keys.KEY_MARKET_UPDATE, "Lcom/xiaomi/market/ui/DownloadListFragment;->parseRecommendGroupResult(Lorg/json/JSONObject;)Lcom/xiaomi/market/viewmodels/RecommendGroupResult;", null))
        add(MarketTarget(Keys.KEY_MARKET_MINE, "Lcom/xiaomi/market/common/analytics/onetrack/ExperimentManager\$Companion;->getMineAdRecommendGroup()I", 0))
        add(MarketTarget(Keys.KEY_MARKET_MINE, "Lcom/xiaomi/market/business_ui/main/mine/view/MineUpdateView;->needApplyOrchardUIState()Z"))
        add(MarketTarget(Keys.KEY_MARKET_PUSH_DIALOG, "Lcom/xiaomi/market/data/NotificationRecallController;->tryShowDialog(Lcom/xiaomi/market/ui/BaseActivity;I)Z"))
        add(MarketTarget(Keys.KEY_MARKET_PUSH_DIALOG, "Lcom/xiaomi/market/data/NotificationRecallController;->checkAndTryShowDialog()Z"))
        add(MarketTarget(Keys.KEY_MARKET_PUSH_DIALOG, "Lcom/xiaomi/market/ui/update/UpdatePushDialogManager;->tryShowDialog(Landroid/app/Activity;Lcom/xiaomi/market/model/RefInfo;)V", null))
        listOf("", "\$Companion").forEach {
            add(MarketTarget(Keys.KEY_MARKET_MARKETING_ICON, "Lcom/xiaomi/market/customize_icon/CustomizeIconDataEditor$it;->isSystemSupportCustomizeIcon()Z"))
        }
    }

    /** Copy only a validated request map; never mutate a shared/unmodifiable host map. */
    fun withoutSuggestionAds(original: Any?): Map<*, *>? {
        val map = original as? Map<*, *> ?: return null
        if (!map.containsKey("adFlag")) return null
        return LinkedHashMap<Any?, Any?>(map).apply { put("adFlag", 0) }
    }
}
