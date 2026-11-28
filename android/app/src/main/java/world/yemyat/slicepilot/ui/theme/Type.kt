package world.yemyat.slicepilot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AppFont = FontFamily.SansSerif

val ElegooTypography =
    Typography(
        headlineSmall =
            TextStyle(
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 0.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = AppFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = AppFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 0.1.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = AppFont,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = AppFont,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                letterSpacing = 0.2.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = AppFont,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                letterSpacing = 0.2.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = AppFont,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                letterSpacing = 0.3.sp,
            ),
        /**
         * The eyebrow every section header uses ([world.yemyat.slicepilot.ui.BrandSectionLabel]).
         * Tracking here is sized for the type size instead of being a fixed 1.2sp, which used to
         * spread mixed-case names apart, and it is bigger than body text so a header never looks
         * smaller than the cards under it.
         */
        labelSmall =
            TextStyle(
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.4.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = AppFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                letterSpacing = 0.4.sp,
            ),
    )
