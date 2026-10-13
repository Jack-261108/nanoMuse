package io.github.nanomuse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import io.github.nanomuse.hub.Device

object NmDeviceMentionView {
    /**
     * Renders the "@<device name>" section inside the unified mention popup (contract C7, rule 8).
     * Displayed above the files section when the mention starts at the beginning of the composer.
     */
    fun renderSection(
        scope: LazyListScope,
        suggestions: List<Device>,
        hasFiles: Boolean,
        onSelectDevice: (Device) -> Unit,
    ) {
        if (suggestions.isEmpty()) return

        scope.item(key = "nm_section_devices_header") {
            Text(
                text = stringResource(R.string.nm_devices_title).uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.secondaryText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        scope.items(suggestions, key = { "nm_device_${it.id}" }) { device ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectDevice(device) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (device.isPhone) Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer,
                    contentDescription = null,
                    tint = ChatColors.sendButton,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "@${device.name}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = ChatColors.primaryText,
                    )
                    Text(
                        text = stringResource(
                            if (device.online) R.string.nm_mention_device_online
                            else R.string.nm_mention_device_offline,
                        ),
                        fontSize = 11.sp,
                        color = ChatColors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (device.online) Color(0xFF2FB344) else ChatColors.secondaryText.copy(alpha = 0.35f),
                            CircleShape,
                        ),
                )
            }
        }

        if (hasFiles) {
            scope.item(key = "nm_devices_files_divider") {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = ChatColors.toolBorder.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }

    /**
     * Renders the "FILES" header above the file entries when devices are also shown in the mention popup.
     */
    fun renderFileHeader(scope: LazyListScope) {
        scope.item(key = "nm_section_files_header") {
            Text(
                text = stringResource(R.string.nm_connectors_cat_files).uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.secondaryText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * A small indicator line above the composer showing which device this message will be sent to.
 */
@Composable
fun NmDeviceTargetBadge(
    target: Device,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (target.isPhone) Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer,
            contentDescription = null,
            tint = ChatColors.sendButton,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.nm_mention_device_target, target.name),
            fontSize = 11.5.sp,
            color = ChatColors.secondaryText,
        )
    }
}
