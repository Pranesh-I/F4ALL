package com.sai.sports.ui.onboarding

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.i18n.AppLanguage
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.ui.common.ScreenTitle
import com.sai.sports.ui.kit.InkButton
import com.sai.sports.ui.kit.LaneLines
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.RuleLine
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.theme.TrialsColors
import com.sai.sports.ui.theme.TrialsFonts
import java.util.Locale

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
        Page(header = { LaneLines(Modifier.padding(top = 20.dp)) }, spacing = 20.dp) {
            // Deliberately multilingual: the athlete has not chosen yet. Each
            // line is set in its own script's face, so none of them looks foreign.
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { heading() },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LANGUAGE_PROMPTS.forEach { (language, prompt) ->
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = TrialsFonts.text(language))
                    )
                }
            }
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

    Page(header = { LaneLines(Modifier.padding(top = 20.dp)) }, spacing = 20.dp) {
        // Three lanes, one per page: how far through the athlete is.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            pages.indices.forEach { lane ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (lane <= index) TrialsColors.Ink else TrialsColors.Rule)
                )
            }
        }
        Text(
            stringResource(R.string.onboarding_step, index + 1, pages.size).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            color = TrialsColors.Pencil
        )
        Sheet(padding = PaddingValues(24.dp)) {
            Image(
                painter = painterResource(current.image),
                // Decorative: the heading and body carry the meaning.
                contentDescription = null,
                colorFilter = ColorFilter.tint(TrialsColors.Ink),
                modifier = Modifier
                    .height(150.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }
        ScreenTitle(stringResource(current.title))
        Text(stringResource(current.body), style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            LineButton(
                text = stringResource(R.string.action_back),
                onClick = { page -= 1 },
                modifier = Modifier.weight(1f)
            )
            InkButton(
                onClick = {
                    if (index == pages.lastIndex) {
                        OnboardingStore.markComplete(context)
                        onFinished()
                    } else {
                        page += 1
                    }
                },
                modifier = Modifier.weight(1f),
                text = stringResource(
                    if (index == pages.lastIndex) R.string.onboarding_start else R.string.action_next
                )
            )
        }
    }
}

/** "Choose your language", in each language, for the very first screen. */
private val LANGUAGE_PROMPTS = listOf(
    AppLanguage.ENGLISH to "Choose your language",
    AppLanguage.HINDI to "अपनी भाषा चुनें",
    AppLanguage.TAMIL to "உங்கள் மொழியைத் தேர்ந்தெடுக்கவும்",
    AppLanguage.BENGALI to "আপনার ভাষা বেছে নিন"
)

private data class IntroPage(
    @DrawableRes val image: Int,
    @StringRes val title: Int,
    @StringRes val body: Int
)

/**
 * One radio row per language, each labelled in its own script and set in that
 * script's face, so a Tamil reader sees Tamil that looks like Tamil, whatever
 * language the screen is in.
 */
@Composable
fun LanguagePicker(selected: AppLanguage?, onSelected: (AppLanguage) -> Unit) {
    Column(Modifier.selectableGroup()) {
        AppLanguage.entries.forEach { language ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = language == selected,
                        role = Role.RadioButton,
                        onClick = { onSelected(language) }
                    )
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RadioButton(selected = language == selected, onClick = null)
                Text(
                    language.nativeName,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = TrialsFonts.text(language))
                )
            }
            RuleLine()
        }
    }
}
