package com.naveen.civilscompanion.ui.read

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.reader.ReadingPosition
import com.naveen.civilscompanion.reader.readingPercent
import com.naveen.civilscompanion.reader.statusLook
import com.naveen.civilscompanion.reader.typeLabel
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.capture.CaptureTarget
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ReadHomeViewModel @Inject constructor(store: RecordStore) : ViewModel() {
    val recent: StateFlow<List<LibDocument>> = store.observe(Tables.Documents, RecordQuery(order = Order.NewestFirst, limit = 30))
        .map { list -> list.filter { !it.deleted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** The "Read" rail item when no document is open: pick up where you stopped, or choose something else. */
@Composable
fun ReadHomeScreen(nav: NavHostController, vm: ReadHomeViewModel = hiltViewModel()) {
    val docs by vm.recent.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(Cc.colors.background).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(
            title = "Read",
            subtitle = "Read aloud with the sentence highlighted, even offline",
            actions = {
                BigButton("Open the Library", onClick = { nav.navigate(Routes.LIBRARY) }, filled = false)
                BigButton("Scan a book page", onClick = { CaptureTarget.documentId = null; nav.navigate(Routes.CAPTURE) })
            },
        )
        if (docs.isEmpty()) {
            EmptyState("Nothing to read yet", "Upload a PDF in the Library, or scan a page with the camera.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(docs, key = { it.id }) { doc ->
                val pos = ReadingPosition.from(doc.readingPosition)
                val look = statusLook(doc.processingStatus, doc.statusDetail)
                CcCard(Modifier.fillMaxWidth(), onClick = { nav.navigate(Routes.readDoc(doc.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(doc.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill(typeLabel(doc.type))
                                Pill(look.label, tone = look.tone)
                                if (pos.page > 1 && doc.pages > 0) Pill("Stopped at page ${pos.page} (${readingPercent(pos.page, doc.pages)}%)")
                            }
                        }
                        BigButton(if (pos.page > 1) "Continue" else "Open", onClick = { nav.navigate(Routes.readDoc(doc.id)) })
                    }
                }
            }
        }
    }
}
