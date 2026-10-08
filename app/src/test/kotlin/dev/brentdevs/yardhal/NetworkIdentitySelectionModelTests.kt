package dev.brentdevs.yardhal

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NetworkIdentitySelectionModelTests {
    @Test
    fun selectionCompletesForTheCurrentEditorAfterActivityRecreation() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val originalOwner = activity.get()
        val callbackModel = ViewModelProvider(originalOwner)[NetworkIdentitySelectionModel::class.java]
        val requestId = callbackModel.begin("saved-editor-id")
        val configuration = Configuration(originalOwner.resources.configuration).apply {
            orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }
        }
        activity.configurationChange(configuration)
        val recreatedModel = ViewModelProvider(activity.get())[NetworkIdentitySelectionModel::class.java]
        assertSame(callbackModel, recreatedModel)
        assertTrue(recreatedModel.selection.value.busy)
        callbackModel.complete(requestId, "selected-client-identity")
        val result = recreatedModel.selection.value
        assertEquals("saved-editor-id", result.editorId)
        assertEquals("selected-client-identity", result.alias)
        assertFalse(result.busy)
        recreatedModel.consume(requestId)
        assertNull(recreatedModel.selection.value.requestId)
        activity.pause().stop().destroy()
    }

    @Test
    fun supersededChooserResultsCannotReplaceAnotherEditorsIdentity() {
        val model = NetworkIdentitySelectionModel()
        val retired = model.begin("first-editor")
        val current = model.begin("second-editor")
        model.complete(retired, "wrong-network-identity")
        model.fail(retired)
        assertEquals("second-editor", model.selection.value.editorId)
        assertTrue(model.selection.value.busy)
        assertNull(model.selection.value.alias)
        model.complete(current, "correct-network-identity")
        assertEquals("correct-network-identity", model.selection.value.alias)
        model.complete(retired, "late-wrong-network-identity")
        assertEquals("correct-network-identity", model.selection.value.alias)
    }

    @Test
    fun cancellationAndLaunchFailureClearBusyAndAllowRetry() {
        val model = NetworkIdentitySelectionModel()
        model.complete(model.begin("editor"), null)
        assertFalse(model.selection.value.busy)
        assertTrue(model.selection.value.error.orEmpty().contains("No identity was selected"))
        model.fail(model.begin("editor"))
        assertFalse(model.selection.value.busy)
        assertTrue(model.selection.value.error.orEmpty().contains("couldn't open"))
        val retry = model.begin("editor")
        assertTrue(model.selection.value.busy)
        assertNull(model.selection.value.error)
        model.consume(retry)
        assertTrue(model.selection.value.busy)
        model.complete(retry, "client")
        assertEquals("client", model.selection.value.alias)
        assertNull(model.selection.value.error)
    }
}
