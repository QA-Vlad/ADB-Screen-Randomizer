package io.github.qavlad.adbrandomizer.ui.components

import com.android.ddmlib.IDevice
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.services.DeviceInfo
import io.github.qavlad.adbrandomizer.services.WifiDeviceHistoryService
import io.github.qavlad.adbrandomizer.ui.renderers.DeviceListRenderer
import io.github.qavlad.adbrandomizer.ui.renderers.DeviceInfoPanelRenderer
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import com.intellij.ide.util.PropertiesComponent
import javax.swing.JOptionPane
import javax.swing.JCheckBox
import javax.swing.Icon
import java.awt.FlowLayout
import com.intellij.icons.AllIcons

sealed class DeviceListItem {
    data class SectionHeader(val title: String) : DeviceListItem()
    data class Device(val info: DeviceInfo, val isConnected: Boolean) : DeviceListItem()
    data class WifiHistoryDevice(val entry: WifiDeviceHistoryService.WifiDeviceHistoryEntry) : DeviceListItem()
}

class DeviceListPanel(
    private val getHoverState: () -> HoverState,
    private val setHoverState: (HoverState) -> Unit,
    private val getAllDevices: () -> List<DeviceInfo>,
    private val onMirrorClick: (DeviceInfo) -> Unit,
    private val onWifiClick: (IDevice) -> Unit,
    private val compactActionPanel: CompactActionPanel,
    private val onForceUpdate: () -> Unit  // Новый callback для форсированного обновления
) : JPanel(BorderLayout()) {

    companion object {
        private const val CONFIRM_DELETE_KEY = "adbrandomizer.skipWifiHistoryDeleteConfirm"
        private val DELETE_ICON: Icon = AllIcons.Actions.GC
        private const val DELETE_BUTTON_WIDTH = 35
        private const val DELETE_BUTTON_HEIGHT = 25
        private const val DELETE_BUTTON_RIGHT_MARGIN = 10
    }

    private val deviceListModel = DefaultListModel<DeviceListItem>()
    private val deviceList = JBList(deviceListModel)
    private val properties = PropertiesComponent.getInstance()
    private val deviceInfoRenderer = DeviceInfoPanelRenderer()

    init {
        setupUI()
        setupDeviceListInteractions()
    }

    private fun setupUI() {
        val titleLabel = JLabel("Devices").apply {
            border = JBUI.Borders.empty()
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                BorderFactory.createMatteBorder(1, 1, 1, 1, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                JBUI.Borders.empty(4, 8)
            )
            add(titleLabel, BorderLayout.WEST)
            add(compactActionPanel, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
            preferredSize = Dimension(preferredSize.width, JBUI.scale(36))
        }
        deviceList.cellRenderer = object : ListCellRenderer<DeviceListItem> {
            private val defaultRenderer = DeviceListRenderer(
                getHoverState = getHoverState,
                getAllDevices = getAllDevices,
                onMirrorClick = onMirrorClick,
                onWifiClick = onWifiClick
            )
            override fun getListCellRendererComponent(
                list: JList<out DeviceListItem>, value: DeviceListItem, index: Int, selected: Boolean, focused: Boolean
            ): java.awt.Component {
                return when (value) {
                    is DeviceListItem.SectionHeader -> JLabel(value.title).apply {
                        font = font.deriveFont(font.style or java.awt.Font.BOLD)
                        border = JBUI.Borders.empty(8, 8, 4, 8)
                    }
                    is DeviceListItem.Device -> {
                        @Suppress("UNCHECKED_CAST")
                        defaultRenderer.getListCellRendererComponent(
                            list as JList<DeviceInfo>, value.info, index, selected, focused
                        )
                    }
                    is DeviceListItem.WifiHistoryDevice -> {
                        val panel = JPanel(BorderLayout(10, 0)).apply {
                            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                            background = list.background
                            isOpaque = true
                        }
                        val fakeDeviceInfo = DeviceInfo(
                            device = null,
                            displayName = value.entry.displayName,
                            displaySerialNumber = value.entry.realSerialNumber ?: value.entry.logicalSerialNumber,  // Используем настоящий серийник, если есть
                            logicalSerialNumber = "${value.entry.ipAddress}:${value.entry.port}",  // Создаём Wi-Fi серийник
                            androidVersion = value.entry.androidVersion,
                            apiLevel = value.entry.apiLevel,
                            ipAddress = value.entry.ipAddress
                        )
                        val infoPanel = deviceInfoRenderer.createInfoPanel(
                            deviceInfo = fakeDeviceInfo,
                            allDevices = emptyList(),
                            listForeground = list.foreground
                        )
                        panel.add(infoPanel, BorderLayout.CENTER)
                        
                        // Проверяем, находится ли курсор над этим элементом
                        val isHovered = getHoverState().hoveredDeviceIndex == index && 
                                      getHoverState().hoveredButtonType == "DELETE"
                        
                        val deleteButton = JButton(DELETE_ICON).apply {
                            isFocusable = false
                            isContentAreaFilled = isHovered
                            isBorderPainted = isHovered
                            isRolloverEnabled = true
                            toolTipText = "Delete from history"
                            preferredSize = Dimension(DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)
                            if (isHovered) {
                                background = JBUI.CurrentTheme.ActionButton.hoverBackground()
                            }
                        }
                        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                            isOpaque = false
                            preferredSize = Dimension(115, 30)
                            add(deleteButton)
                        }
                        panel.add(buttonPanel, BorderLayout.EAST)
                        panel
                    }
                }
            }
        }
        deviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        deviceList.emptyText.text = "No devices found"
        deviceList.clearSelection()
        deviceList.selectionModel.clearSelection()
        val scrollPane = JBScrollPane(deviceList)
        scrollPane.border = BorderFactory.createEmptyBorder()
        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun setupDeviceListInteractions() {
        deviceList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                handleMouseMovement(e)
            }
        })
        deviceList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                handleMouseClick(e)
            }
            override fun mouseExited(event: MouseEvent?) {
                resetHoverState()
            }
        })
    }

    private fun handleMouseMovement(e: MouseEvent) {
        val index = deviceList.locationToIndex(e.point)
        if (index == -1 || index >= deviceListModel.size()) return
        val item = deviceListModel.getElementAt(index)
        
        when (item) {
            is DeviceListItem.Device -> {
                val bounds = deviceList.getCellBounds(index, index)
                val deviceInfo = item.info
                val buttonLayout = DeviceConnectionUtils.calculateButtonLayout(
                    bounds,
                    DeviceConnectionUtils.isWifiConnection(deviceInfo.logicalSerialNumber)
                )
                val newButtonType = when {
                    buttonLayout.mirrorButtonRect.contains(e.point) -> HoverState.BUTTON_TYPE_MIRROR
                    buttonLayout.wifiButtonRect?.contains(e.point) == true -> HoverState.BUTTON_TYPE_WIFI
                    else -> null
                }
                updateCursorAndHoverState(index, newButtonType)
            }
            is DeviceListItem.WifiHistoryDevice -> {
                val bounds = deviceList.getCellBounds(index, index)
                val deleteButtonRect = getDeleteButtonRect(bounds)
                if (deleteButtonRect.contains(e.point)) {
                    deviceList.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    val newHoverState = HoverState(hoveredDeviceIndex = index, hoveredButtonType = "DELETE")
                    if (getHoverState() != newHoverState) {
                        setHoverState(newHoverState)
                        deviceList.repaint()
                    }
                } else {
                    deviceList.cursor = Cursor.getDefaultCursor()
                    if (getHoverState().hoveredDeviceIndex == index) {
                        setHoverState(HoverState.noHover())
                        deviceList.repaint()
                    }
                }
            }
            else -> {
                deviceList.cursor = Cursor.getDefaultCursor()
                setHoverState(HoverState.noHover())
            }
        }
    }

    private fun updateCursorAndHoverState(index: Int, newButtonType: String?) {
        deviceList.cursor = if (newButtonType != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
        val currentHoverState = getHoverState()
        val newHoverState = if (index != -1 && newButtonType != null) {
            HoverState(hoveredDeviceIndex = index, hoveredButtonType = newButtonType)
        } else {
            HoverState.noHover()
        }
        if (currentHoverState != newHoverState) {
            setHoverState(newHoverState)
            deviceList.repaint()
        }
    }

    private fun handleMouseClick(e: MouseEvent) {
        val index = deviceList.locationToIndex(e.point)
        if (index == -1 || index >= deviceListModel.size()) return
        val item = deviceListModel.getElementAt(index)
        
        when (item) {
            is DeviceListItem.Device -> {
                val bounds = deviceList.getCellBounds(index, index)
                val deviceInfo = item.info
                val buttonLayout = DeviceConnectionUtils.calculateButtonLayout(
                    bounds,
                    DeviceConnectionUtils.isWifiConnection(deviceInfo.logicalSerialNumber)
                )
                when {
                    buttonLayout.mirrorButtonRect.contains(e.point) -> onMirrorClick(deviceInfo)
                    buttonLayout.wifiButtonRect?.contains(e.point) == true -> deviceInfo.device?.let { onWifiClick(it) }
                }
            }
            is DeviceListItem.WifiHistoryDevice -> {
                val bounds = deviceList.getCellBounds(index, index)
                val deleteButtonRect = getDeleteButtonRect(bounds)
                if (deleteButtonRect.contains(e.point)) {
                    handleDeleteHistoryDevice(item.entry)
                }
            }
            else -> return
        }
        deviceList.clearSelection()
    }

    private fun resetHoverState() {
        val currentHoverState = getHoverState()
        if (currentHoverState.hoveredDeviceIndex != -1 && currentHoverState.hoveredButtonType != null) {
            setHoverState(HoverState.noHover())
            deviceList.cursor = Cursor.getDefaultCursor()
            deviceList.repaint()
        }
    }

    fun updateDeviceList(devices: List<DeviceInfo>) {
        SwingUtilities.invokeLater {
            deviceListModel.clear()
            // 1. Подключённые устройства
            if (devices.isNotEmpty()) {
                deviceListModel.addElement(DeviceListItem.SectionHeader("Connected devices"))
                devices.forEach {
                    deviceListModel.addElement(DeviceListItem.Device(it, true))
                }
            }
            // 2. Ранее подключённые по Wi-Fi
            val history = WifiDeviceHistoryService.getHistory()
            val connectedSerials = devices.map { it.logicalSerialNumber }.toSet()
            val notConnectedHistory = history.filter { it.logicalSerialNumber !in connectedSerials }
            if (notConnectedHistory.isNotEmpty()) {
                deviceListModel.addElement(DeviceListItem.SectionHeader("Previously connected devices"))
                notConnectedHistory.forEach {
                    deviceListModel.addElement(DeviceListItem.WifiHistoryDevice(it))
                }
            }
        }
    }

    fun getDeviceListModel(): DefaultListModel<DeviceListItem> = deviceListModel

    /**
     * Вычисляет прямоугольник кнопки удаления
     */
    private fun getDeleteButtonRect(bounds: java.awt.Rectangle): java.awt.Rectangle {
        val deleteButtonX = bounds.x + bounds.width - DELETE_BUTTON_WIDTH - DELETE_BUTTON_RIGHT_MARGIN
        val deleteButtonY = bounds.y + (bounds.height - DELETE_BUTTON_HEIGHT) / 2
        return java.awt.Rectangle(deleteButtonX, deleteButtonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)
    }

    private fun handleDeleteHistoryDevice(entry: WifiDeviceHistoryService.WifiDeviceHistoryEntry) {
        val skipConfirm = properties.getBoolean(CONFIRM_DELETE_KEY, false)
        var doDelete = true
        if (!skipConfirm) {
            val checkbox = JCheckBox("Don't ask again")
            val result = JOptionPane.showConfirmDialog(
                this,
                arrayOf("Delete device from history?", checkbox),
                "Confirm deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (checkbox.isSelected) {
                properties.setValue(CONFIRM_DELETE_KEY, true)
            }
            doDelete = result == JOptionPane.YES_OPTION
        }
        if (doDelete) {
            val newHistory = WifiDeviceHistoryService.getHistory().filterNot {
                it.ipAddress == entry.ipAddress && it.port == entry.port
            }
            WifiDeviceHistoryService.saveHistory(newHistory)
            // Обновляем список
            updateDeviceList(getAllDevices())
            // Форсируем обновление списка устройств
            onForceUpdate()
        }
    }
}