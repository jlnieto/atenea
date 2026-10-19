package com.atenea.android.coreconsole

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.atenea.android.api.*
import com.atenea.android.secure.AteneaSessionStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AteneaUpdatesScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test fun vectorBellHasAccessibleUnreadBadgeAndOneClickAction() {
        var clicks=0
        compose.setContent { AteneaOperatorTheme { AteneaNotificationsButton(2) {clicks++} } }
        compose.onNodeWithContentDescription("Notificaciones, 2 sin leer").assertIsDisplayed().performClick()
        assertEquals(1,clicks)
        val image=compose.onRoot().captureToImage().asAndroidBitmap()
        val target=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"notifications-bell.png")
        target.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun newsCacheIsIsolatedByOperatorAndClearedAtLogout() {
        val store=AteneaSessionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        fun session(id: Long)=MobileAuthSession("synthetic-access","2099-01-01T00:00:00Z","synthetic-refresh","2099-01-01T00:00:00Z",OperatorProfile(id,"synthetic@test.invalid","Synthetic"))
        store.clear()
        try {
            store.save(session(1));store.saveProductNews(1,"synthetic-news");assertEquals("synthetic-news",store.loadProductNews())
            store.save(session(1));assertEquals("synthetic-news",store.loadProductNews())
            store.save(session(2));assertEquals(null,store.loadProductNews())
            store.saveProductNews(1,"foreign-news");assertEquals(null,store.loadProductNews())
            store.saveProductNews(2,"own-news");store.clear();assertEquals(null,store.loadProductNews())
        } finally { store.clear() }
    }
    @Test fun cachedNewsStaysReadableWhileRefreshingAndHighlightsAreNotDuplicated() {
        val ssh=fixture().entries.first().copy(id="app:72",title="Menos avisos falsos al comprobar el servidor",summary="Un fallo SSH no equivale a una caída de Apache.")
        compose.setContent { AteneaOperatorTheme { AteneaUpdatesContent(fixture().copy(historyState="CACHED",highlights=listOf(ssh),refreshState="REFRESHING"),164,"0.5.131",true,null,
            onRefresh={},onMore={},onOpenUpdates={}) } }
        visible("Actualizando en segundo plano. Puedes seguir leyendo.")
        visible("Mejora destacada")
        visible("Menos avisos falsos al comprobar el servidor")
    }
    private fun visible(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("atenea-updates").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).assertIsDisplayed()
    }
    private fun fixture()=AteneaProductUpdates("2026-10-15T12:00:00Z","CURRENT",listOf(
        AteneaProductEntry("app:71","Imágenes en tu conversación","Adjunta sin crear otra sesión.","2026-10-15T10:00:00Z","APP",
            listOf(MobileDeliveryTarget.ANDROID_STABLE),mapOf(MobileDeliveryTarget.ANDROID_STABLE to "NOT_INCLUDED"),
            "https://github.com/jlnieto/atenea/pull/71","7".repeat(40)),
        AteneaProductEntry("platform:72","Worker actualizado","","2026-10-15T09:00:00Z","PLATFORM",
            listOf(MobileDeliveryTarget.AX42_PLATFORM),mapOf(MobileDeliveryTarget.AX42_PLATFORM to "PUBLISHED"),
            "https://github.com/jlnieto/atenea-remote-worker-spec/pull/72","6".repeat(40))),
        AteneaInstalledComponent("8".repeat(40),true),MobileAndroidChannel("8".repeat(40),"0.6.42",1000042,"a".repeat(64),100),
        AteneaInstalledComponent("6".repeat(40),null),true,null,emptyList())

    @Test fun showsInstallActionAndGlobalComponentHistoryWithoutExecutingAnything() {
        var refreshes=0;var updates=0
        compose.setContent { AteneaOperatorTheme { AteneaUpdatesContent(fixture(),155,"0.5.122",false,null,
            onRefresh={refreshes++},onMore={},onOpenUpdates={updates++}) } }
        visible("Actualizar Atenea").performClick();assertEquals(1,updates)
        visible("Actualizar información").performClick();assertEquals(1,refreshes)
        visible("Imágenes en tu conversación")
        visible("Android estable: integrado, aún no publicado")
        visible("Worker actualizado")
        visible("Worker AX42: disponible")
    }
    @Test fun targetFiltersAreLocalAndMissingEvidenceIsNotPresentedAsPublished() {
        var calls=0
        compose.setContent { AteneaOperatorTheme { AteneaUpdatesContent(fixture().copy(android=null,platform=null),155,"0.5.122",false,null,
            onRefresh={calls++},onMore={calls++},onOpenUpdates={calls++}) } }
        visible("La versión del canal está pendiente de confirmar.")
        visible("Worker").performClick()
        visible("Worker actualizado")
        compose.onNodeWithText("Imágenes en tu conversación").assertDoesNotExist();assertEquals(0,calls)
    }
}
