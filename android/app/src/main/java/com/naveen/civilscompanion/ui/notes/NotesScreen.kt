package com.naveen.civilscompanion.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes

/** Notes (spec 6.6): the subject and topic tree on the left, the topic's notes on the right. */
@Composable
fun NotesScreen(nav: NavHostController, initialTopicId: String?, vm: NotesViewModel = hiltViewModel()) {
    val list by vm.list.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    LaunchedEffect(initialTopicId) {
        if (initialTopicId != null) vm.select(initialTopicId)
    }
    if (isCompact()) {
        // Upright tablet: the topic list and the topic's notes take turns, with a Back button on the notes.
        if (list.selectedId == null) {
            NotesTreePane(
                s = list,
                vm = vm,
                onOpenSyllabus = { nav.navigate(Routes.SYLLABUS) },
                modifier = Modifier.fillMaxSize().background(Cc.colors.rail),
            )
        } else {
            Column(Modifier.fillMaxSize().background(Cc.colors.background)) {
                BigButton(
                    "Back to topics", onClick = { vm.select(null) }, filled = false,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
                NoteDetailPane(
                    nav = nav,
                    s = list,
                    d = detail,
                    vm = vm,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        return
    }
    Row(Modifier.fillMaxSize().background(Cc.colors.background)) {
        NotesTreePane(
            s = list,
            vm = vm,
            onOpenSyllabus = { nav.navigate(Routes.SYLLABUS) },
            modifier = Modifier.width(360.dp).fillMaxHeight().background(Cc.colors.rail),
        )
        NoteDetailPane(
            nav = nav,
            s = list,
            d = detail,
            vm = vm,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}
