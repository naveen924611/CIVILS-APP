package com.naveen.civilscompanion.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
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
import com.naveen.civilscompanion.ui.nav.Routes

/** Notes (spec 6.6): the subject and topic tree on the left, the topic's notes on the right. */
@Composable
fun NotesScreen(nav: NavHostController, initialTopicId: String?, vm: NotesViewModel = hiltViewModel()) {
    val list by vm.list.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    LaunchedEffect(initialTopicId) {
        if (initialTopicId != null) vm.select(initialTopicId)
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
