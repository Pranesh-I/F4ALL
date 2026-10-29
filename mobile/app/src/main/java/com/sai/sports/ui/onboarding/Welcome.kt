package com.sai.sports.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.Eyebrow
import com.sai.sports.ui.kit.GlowBackdrop
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.MarigoldButton
import com.sai.sports.ui.kit.Tag
import com.sai.sports.ui.kit.TagTone
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.delay

/** The app's mark: a bolt on an orange tile. */
@Composable
fun AppMark(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(AppColors.Accent, RoundedCornerShape(size * 0.3f))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        Icon(AppIcons.Bolt, contentDescription = null, tint = AppColors.OnAccent, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The first frame: the mark and the name, lit from the corner, for under a
 * second while the app decides where to go. Never a wait for the network.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fade.animateTo(1f, tween(450))
        delay(SPLASH_HOLD_MS)
        onDone()
    }
    GlowBackdrop(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(fade.value)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            AppMark(size = 84.dp)
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary
            )
        }
    }
}

private const val SPLASH_HOLD_MS = 650L

/**
 * Two doors, clearly labelled: a new athlete creates an account, a returning
 * one signs in. Before this screen existed, everyone landed on a phone-number
 * form with no idea which they were doing.
 */
@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onSignIn: () -> Unit) {
    GlowBackdrop(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppMark(size = 44.dp)
                Eyebrow(stringResource(R.string.app_name), color = AppColors.TextPrimary)
            }

            Spacer(Modifier.weight(1f))

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Tag(stringResource(R.string.welcome_tag), tone = TagTone.Accent, icon = AppIcons.Shield)
                Text(
                    stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = MaterialTheme.typography.headlineLarge.fontSize * 1.2f,
                        lineHeight = MaterialTheme.typography.headlineLarge.lineHeight * 1.2f
                    ),
                    color = AppColors.TextPrimary,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    stringResource(R.string.welcome_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextSecondary
                )
            }

            Spacer(Modifier.height(32.dp))

            MarigoldButton(
                text = stringResource(R.string.welcome_create),
                onClick = onCreateAccount,
                icon = AppIcons.Plus,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            LineButton(
                text = stringResource(R.string.welcome_sign_in),
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
