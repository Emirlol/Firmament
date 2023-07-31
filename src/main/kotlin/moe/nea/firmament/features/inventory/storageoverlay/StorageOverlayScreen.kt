/*
 * SPDX-FileCopyrightText: 2023 Linnea Gräf <nea@nea.moe>
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package moe.nea.firmament.features.inventory.storageoverlay

import moe.nea.firmament.util.MC
import moe.nea.firmament.util.assertNotNullOr
import moe.nea.firmament.util.toShedaniel
import net.minecraft.block.Blocks
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.Item
import net.minecraft.item.Items
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket
import net.minecraft.text.Text
import net.minecraft.util.DyeColor
import kotlin.math.max

class StorageOverlayScreen() : Screen(Text.empty()) {
    companion object {
        val emptyStorageSlotItems = listOf<Item>(
            Blocks.RED_STAINED_GLASS_PANE.asItem(),
            Blocks.BROWN_STAINED_GLASS_PANE.asItem(),
            Items.GRAY_DYE
        )
        val pageWidth get() = 19 * 9
    }

    private var handler: StorageBackingHandle = StorageBackingHandle.None
    val content = StorageOverlay.Data.data ?: StorageData()
    var isClosing = false

    private fun discardOldHandle() {
        val player = assertNotNullOr(MC.player) { return }
        val handle = this.handler
        if (handle is StorageBackingHandle.HasBackingScreen) {
            player.networkHandler.sendPacket(CloseHandledScreenC2SPacket(handle.handler.syncId))
            if (player.currentScreenHandler === handle.handler) {
                player.currentScreenHandler = player.playerScreenHandler
            }
        }
    }

    fun setHandler(handler: StorageBackingHandle?) {
        discardOldHandle()
        if (handler != null)
            this.handler = handler
    }

    var scroll = 0
    var lastRenderedHeight = 0

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.fill(0, 0, width, height, 0x90000000.toInt())
        layoutedForEach { (key, value), offsetX, offsetY ->
            context.matrices.push()
            context.matrices.translate(offsetX.toFloat(), offsetY.toFloat(), 0F)
            renderStoragePage(context, value, mouseX - offsetX, mouseY - offsetY)
            context.matrices.pop()
        }
    }

    inline fun layoutedForEach(onEach: (data: Pair<StoragePageSlot, StorageData.StorageInventory>, offsetX: Int, offsetY: Int) -> Unit) {
        var offsetY = 0
        var currentMaxHeight = StorageOverlay.config.margin - StorageOverlay.config.padding - scroll
        var totalHeight = -currentMaxHeight
        content.storageInventories.onEachIndexed { index, (key, value) ->
            val pageX = (index % StorageOverlay.config.rows)
            if (pageX == 0) {
                currentMaxHeight += StorageOverlay.config.padding
                offsetY += currentMaxHeight
                totalHeight += currentMaxHeight
                currentMaxHeight = 0
            }
            val xPosition =
                width / 2 - (StorageOverlay.config.rows * (pageWidth + StorageOverlay.config.padding) - StorageOverlay.config.padding) / 2 + pageX * (pageWidth + StorageOverlay.config.padding)
            onEach(Pair(key, value), xPosition, offsetY)
            val height = getStorePageHeight(value)
            currentMaxHeight = max(currentMaxHeight, height)
        }
        lastRenderedHeight = totalHeight + currentMaxHeight
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        layoutedForEach { (k, p), x, y ->
            val rx = mouseX - x
            val ry = mouseY - y
            if (rx in (0.0..pageWidth.toDouble()) && ry in (0.0..getStorePageHeight(p).toDouble())) {
                close()
                k.navigateTo()
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    fun getStorePageHeight(page: StorageData.StorageInventory): Int {
        return page.inventory?.rows?.let { it * 19 + MC.font.fontHeight + 2 } ?: 60
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        scroll =
            (scroll + amount * StorageOverlay.config.scrollSpeed *
                (if (StorageOverlay.config.inverseScroll) 1 else -1)).toInt()
                .coerceAtMost(lastRenderedHeight - height + 2 * StorageOverlay.config.margin).coerceAtLeast(0)
        return true
    }

    private fun renderStoragePage(context: DrawContext, page: StorageData.StorageInventory, mouseX: Int, mouseY: Int) {
        context.drawText(MC.font, page.title, 2, 2, -1, true)
        val inventory = page.inventory
        if (inventory == null) {
            // TODO: Missing texture
            context.fill(0, 0, pageWidth, 60, DyeColor.RED.toShedaniel().darker(4.0).color)
            context.drawCenteredTextWithShadow(MC.font, Text.literal("Not loaded yet"), pageWidth / 2, 30, -1)
            return
        }

        for ((index, stack) in inventory.stacks.withIndex()) {
            val x = (index % 9) * 19
            val y = (index / 9) * 19 + MC.font.fontHeight + 2
            if (((mouseX - x) in 0 until 18) && ((mouseY - y) in 0 until 18)) {
                context.fill(x, y, x + 18, y + 18, 0x80808080.toInt())
            } else {
                context.fill(x, y, x + 18, y + 18, 0x40808080.toInt())
            }
            context.drawItem(stack, x + 1, y + 1)
            context.drawItemInSlot(MC.font, stack, x + 1, y + 1)
        }
    }

    override fun close() {
        discardOldHandle()
        isClosing = true
        super.close()
    }
}
