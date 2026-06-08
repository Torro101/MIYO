package org.koharu.miyo.settings.utils

import android.net.Uri
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import org.koharu.miyo.R
import org.koharu.miyo.reader.domain.BundledImageRefinementModel

class RefinementModelSettingsHelper(
	private val fragment: Fragment,
	private val refinementModel: BundledImageRefinementModel,
) {

	fun importModelsZip(uri: Uri) {
		val result = refinementModel.importModelsZip(uri)
		if (result.isSuccess) {
			val names = result.installed.joinToString(", ")
			Snackbar.make(
				fragment.requireView(),
				fragment.getString(R.string.refinement_models_imported, names),
				Snackbar.LENGTH_LONG,
			).show()
		} else {
			Snackbar.make(
				fragment.requireView(),
				fragment.getString(R.string.refinement_models_import_failed),
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	fun bindModelSummary(preference: androidx.preference.ListPreference) {
		val selected = preference.value ?: BundledImageRefinementModel.DEFAULT_MODEL_ID
		val entryIndex = preference.entryValues?.indexOf(selected) ?: -1
		val label = if (entryIndex >= 0) {
			preference.entries?.getOrNull(entryIndex)?.toString()
		} else {
			selected
		}
		val availability = if (refinementModel.isModelAvailable(selected)) {
			fragment.getString(R.string.available)
		} else {
			fragment.getString(R.string.not_available)
		}
		preference.summary = "$label ($availability)"
	}

	companion object {
		val IMPORT_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed")
	}
}
