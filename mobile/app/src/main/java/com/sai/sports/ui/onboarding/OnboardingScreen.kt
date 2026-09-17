package com.sai.sports.ui.onboarding

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.i18n.AppLanguage
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.ui.common.ScreenTitle

/**
 * First run: language, then three short pages, then sign-in.
 *
 * Language is the very first thing asked, before a single sentence of English,
 * and each option is written in its own script — an athlete who cannot read
 * English must still be able to get past this screen.
 *
 * The pages say the three things an athlete most needs before recording
 * anything: what the app is for, that the phone's score is not the official one,
 * and who sees their videos.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current

    // Page 0 is the language picker; survives the recreate a language change causes.
    var page by rememberSaveable { mutableIntStateOf(if (LanguageStore.current(context) == null) 0 else 1) }

    if (page == 0) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Deliberately multilingual: the athlete has not chosen yet.
            ScreenTitle("Choose your language · अपनी भाषा चुनें · உங்கள் மொழியைத் தேர்ந்தெடுக்கவும் · আপনার ভাষা বেছে নিন")
            LanguagePicker(
                selected = LanguageStore.current(context),
                onSelected = { language ->
                    LanguageStore.set(context, language)
                    page = 1
                    (context as? Activity)?.recreate()
                }
            )
        }
        return
    }

    val pages = listOf(
        IntroPage(R.drawable.ic_intro_record, R.string.onboarding_1_title, R.string.onboarding_1_body),
        IntroPage(R.drawable.ic_intro_verified, R.string.onboarding_2_title, R.string.onboarding_2_body),
        IntroPage(R.drawable.ic_intro_privacy, R.string.onboarding_3_title, R.string.onboarding_3_body)
    )
    val index = (page - 1).coerceIn(0, pages.lastIndex)
    val current = pages[index]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.onboarding_step, index + 1, pages.size),
            style = MaterialTheme.typography.labelLarge
        )
        Image(
            painter = painterResource(current.image),
            // Decorative: the heading and body carry the meaning.
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            modifier = Modifier.height(160.dp)
        )
        ScreenTitle(stringResource(current.title))
        Text(stringResource(current.body), style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { page -= 1 }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_back))
            }
            Button(
                onClick = {
                    if (index == pages.lastIndex) {
                        OnboardingStore.markComplete(context)
                        onFinished()
                    } else {
                        page += 1
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(
                        if (index == pages.lastIndex) R.string.onboarding_start else R.string.action_next
                    )
                )
            }
        }
    }
}

private data class IntroPage(
    @DrawableRes val image: Int,
    @StringRes val title: Int,
    @StringRes val body: Int
)

/** One radio row per language, each labelled in its own script. */
@Composable
fun LanguagePicker(selected: AppLanguage?, onSelected: (AppLanguage) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppLanguage.entries.forEach { language ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = language == selected,
                        role = Role.RadioButton,
                        onClick = { onSelected(language) }
                    )
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RadioButton(selected = language == selected, onClick = null)
                Text(language.nativeName, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
