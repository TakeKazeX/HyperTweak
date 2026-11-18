package com.takekazex.hypertweak.ui.navigation

import androidx.compose.animation.*
import androidx.compose.runtime.*
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.systemui.AodIconsPage
import com.takekazex.hypertweak.ui.page.systemui.SliderPercentagePage
import com.takekazex.hypertweak.ui.page.systemui.SuperIslandPage
import com.takekazex.hypertweak.ui.page.systemui.SystemUIPage
import com.takekazex.hypertweak.ui.page.systemui.controlcenter.ControlCenterIconRowsPage
import com.takekazex.hypertweak.ui.page.systemui.controlcenter.PrivacyIndicatorPage
import com.takekazex.hypertweak.ui.page.systemui.icon.IconOrderPage
import com.takekazex.hypertweak.ui.page.systemui.icon.IconTunerPage
import com.takekazex.hypertweak.ui.page.systemui.notification.MediaCardPage
import com.takekazex.hypertweak.ui.page.systemui.notification.NotificationHeaderPage
import com.takekazex.hypertweak.ui.page.systemui.notification.NotificationPage
import top.yukonga.miuix.kmp.nav.core.*

internal fun NavEntryBuilder.systemPages(settings: NavigationSettings, navController: NavController) {
    with(settings) {
        entry<Route.PrivacyIndicator> {
            PrivacyIndicatorPage(onBack = { navController.pop() })
        }
        entry<Route.IconTuner> {
            IconTunerPage(
                onBack = { navController.pop() },
                onNavigateToIconOrder = { navController.navigateTo(Route.IconOrder) },
                onNavigateToIconRows = { navController.navigateTo(Route.ControlCenterIconRows) }
            )
        }
        entry<Route.ControlCenterIconRows> {
            ControlCenterIconRowsPage(onBack = { navController.pop() })
        }
        entry<Route.IconOrder> {
            IconOrderPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.SystemUi> {
            SystemUIPage(
                onBack = { navController.pop() },
                onNavigateToChargingDetail = {
                    navController.navigateTo(Route.ChargingDetail)
                },
                onNavigateToLockscreenBottomText = {
                    navController.navigateTo(Route.LockscreenBottomText)
                },
                onNavigateToNotifications = { navController.navigateTo(Route.Notifications) },
                onNavigateToMediaCard = { navController.navigateTo(Route.MediaCard) },
                onNavigateToIconTuner = {
                    navController.navigateTo(Route.IconTuner)
                },
                onNavigateToPrivacyIndicator = { navController.navigateTo(Route.PrivacyIndicator) },
                onNavigateToAodIcons = { navController.navigateTo(Route.AodIcons) },
                onNavigateToSliderPercentage = { navController.navigateTo(Route.SliderPercentage) }
            )
        }
        entry<Route.AodIcons> {
            AodIconsPage(onBack = { navController.pop() })
        }
        entry<Route.SliderPercentage> {
            SliderPercentagePage(
                onBack = { navController.pop() },
            )
        }
        entry<Route.Notifications> {
            NotificationPage(
                onBack = { navController.pop() },
                onNavigateToNotificationHeader = { navController.navigateTo(Route.NotificationHeader) },
            )
        }
        entry<Route.MediaCard> {
            MediaCardPage(onBack = { navController.pop() },
            )
        }
        entry<Route.NotificationHeader> {
            NotificationHeaderPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.SuperIsland> {
            SuperIslandPage(
                onBack = { navController.pop() },
            )
        }

    }
}
