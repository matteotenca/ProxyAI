package ee.carlrobert.codegpt.settings.agents.acp

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.external.AcpIcons
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgents
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class AcpAgentSettingsForm(project: Project) {

    private val settings = project.service<AcpAgentSettings>()
    private val selectedPresetIds = linkedSetOf<String>()
    private val presets = ExternalAcpAgents.all()
    private val root = BorderLayoutPanel()
    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Search external agents..."
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getTooltipSeparatorColor(), 1),
            JBUI.Borders.empty(3, 8)
        )
        textEditor.border = JBUI.Borders.empty()
    }
    private val tableModel = AcpAgentTableModel()
    private val rowSorter = TableRowSorter(tableModel)
    private lateinit var scrollPane: JBScrollPane
    private var initialized = false
    private val table = JBTable(tableModel).apply {
        rowSorter = this@AcpAgentSettingsForm.rowSorter
        emptyText.text = "No ACP runtimes match your search."
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        rowHeight = JBUI.scale(28)
        fillsViewportHeight = true
        tableHeader.reorderingAllowed = false
        tableHeader.resizingAllowed = true
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
        setDefaultRenderer(String::class.java, AcpTableCellRenderer())
    }

    init {
        buildUi()
        configureTable()
        installSearch()
        resetChanges()
        initialized = true
        recalculateColumnWidths()
    }

    fun createPanel(): JComponent = root

    fun isModified(): Boolean {
        return selectedPresetIds != settings.getEnabledPresetIds().toSet()
    }

    fun applyChanges() {
        settings.setEnabledPresetIds(selectedPresetIds)
    }

    fun resetChanges() {
        selectedPresetIds.clear()
        selectedPresetIds += settings.getEnabledPresetIds()
        tableModel.fireTableDataChanged()
        refreshFilter()
    }

    private fun buildUi() {
        scrollPane = JBScrollPane(table).apply {
            border = JBUI.Borders.customLine(UIUtil.getTooltipSeparatorColor(), 1)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            viewport.addComponentListener(object : ComponentListener {
                override fun componentResized(e: ComponentEvent) {
                    if (initialized) recalculateColumnWidths()
                }
                override fun componentMoved(e: ComponentEvent) {}
                override fun componentShown(e: ComponentEvent) {}
                override fun componentHidden(e: ComponentEvent) {}
            })
        }

        root.border = JBUI.Borders.empty(8)
        root.addToTop(
            BorderLayoutPanel().apply {
                border = JBUI.Borders.emptyBottom(8)
                addToTop(searchField)
            }
        )
        root.addToCenter(scrollPane)
    }

    private fun configureTable() {
        rowSorter.setSortable(COL_ENABLED, false)
        rowSorter.setSortable(COL_NAME, false)
        rowSorter.setSortable(COL_VENDOR, false)
        rowSorter.setSortable(COL_COMMAND, false)
        rowSorter.setSortable(COL_ARGUMENTS, false)

        val fontMetrics = table.getFontMetrics(table.font)
        val padding = JBUI.scale(16)

        fun measureColumnWidth(colIndex: Int): Int {
            var max = 0
            for (i in presets.indices) {
                val text = tableModel.getValueAt(i, colIndex).toString()
                max = max.coerceAtLeast(fontMetrics.stringWidth(text))
            }
            return JBUI.scale(padding) + max
        }

        val namePref = measureColumnWidth(COL_NAME)
        val vendorPref = measureColumnWidth(COL_VENDOR)
        val commandPref = measureColumnWidth(COL_COMMAND)
        val argsPref = measureColumnWidth(COL_ARGUMENTS)

        table.columnModel.getColumn(COL_ENABLED).apply {
            minWidth = JBUI.scale(36)
            maxWidth = JBUI.scale(36)
            preferredWidth = JBUI.scale(36)
        }
        table.columnModel.getColumn(COL_NAME).apply {
            preferredWidth = namePref
            minWidth = JBUI.scale(50)
        }
        table.columnModel.getColumn(COL_VENDOR).apply {
            preferredWidth = vendorPref
            minWidth = JBUI.scale(50)
        }
        table.columnModel.getColumn(COL_COMMAND).apply {
            preferredWidth = commandPref
            minWidth = JBUI.scale(80)
            cellEditor = DefaultCellEditor(JTextField())
        }
        table.columnModel.getColumn(COL_ARGUMENTS).apply {
            preferredWidth = argsPref
            minWidth = JBUI.scale(60)
            cellEditor = DefaultCellEditor(JTextField())
        }
    }

    private fun recalculateColumnWidths() {
        val enabledCol = table.columnModel.getColumn(COL_ENABLED)
        val nameCol = table.columnModel.getColumn(COL_NAME)
        val vendorCol = table.columnModel.getColumn(COL_VENDOR)
        val commandCol = table.columnModel.getColumn(COL_COMMAND)
        val argsCol = table.columnModel.getColumn(COL_ARGUMENTS)

        val enabledWidth = JBUI.scale(36)
        val namePref = nameCol.preferredWidth
        val viewportWidth = scrollPane.viewport.size.width
        val remaining = viewportWidth - enabledWidth - namePref

        if (remaining <= 0) return

        // Name column always stays at its content-measured width
        nameCol.width = namePref

        val vendorPref = vendorCol.preferredWidth
        val commandPref = commandCol.preferredWidth
        val argsPref = argsCol.preferredWidth
        val minSum = vendorCol.minWidth + commandCol.minWidth + argsCol.minWidth

        if (remaining >= minSum) {
            val totalPref = vendorPref + commandPref + argsPref

            if (remaining >= totalPref) {
                // Plenty of room: Vendor/Command at preferred, Arguments absorbs rest
                vendorCol.width = vendorPref
                commandCol.width = commandPref
                argsCol.width = remaining - vendorCol.width - commandCol.width
            } else {
                // Tight: proportional shrink below preferred but above minWidth
                val ratio = remaining.toDouble() / totalPref
                var allocated = 0

                for ((idx, col) in mapOf(
                    COL_VENDOR to vendorCol,
                    COL_COMMAND to commandCol
                )) {
                    val prefs = mapOf(COL_VENDOR to vendorPref, COL_COMMAND to commandPref)
                    val ideal = (prefs[idx]!! * ratio).toInt().coerceIn(col.minWidth, prefs[idx])
                    col.width = ideal
                    allocated += ideal
                }

                argsCol.width = remaining - allocated
            }
        }

        enabledCol.width = enabledWidth
    }

    private fun installSearch() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                refreshFilter()
            }
        })
    }

    private fun refreshFilter() {
        val query = searchField.text.trim().lowercase()
        rowSorter.rowFilter = if (query.isBlank()) {
            null
        } else {
            object : javax.swing.RowFilter<AcpAgentTableModel, Int>() {
                override fun include(entry: Entry<out AcpAgentTableModel, out Int>): Boolean {
                    val preset = presets[entry.identifier]
                    return listOf(
                        preset.displayName,
                        preset.vendor,
                        preset.description.orEmpty(),
                        preset.fullCommand(),
                        preset.args.joinToString(" ")
                    ).joinToString(" ").lowercase().contains(query)
                }
            }
        }
    }

    private inner class AcpAgentTableModel : AbstractTableModel() {

        override fun getRowCount(): Int = presets.size

        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String {
            return when (column) {
                COL_ENABLED -> ""
                COL_NAME -> "Agent"
                COL_VENDOR -> "Vendor"
                COL_COMMAND -> "Command"
                COL_ARGUMENTS -> "Arguments"
                else -> ""
            }
        }

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == COL_ENABLED) {
                java.lang.Boolean::class.java
            } else {
                String::class.java
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            return columnIndex == COL_ENABLED || columnIndex == COL_COMMAND || columnIndex == COL_ARGUMENTS
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val preset = presets[rowIndex]
            return when (columnIndex) {
                COL_ENABLED -> preset.id in selectedPresetIds
                COL_NAME -> preset.displayName
                COL_VENDOR -> preset.vendor
                COL_COMMAND -> settings.getCustomCommand(preset.id) ?: preset.command
                COL_ARGUMENTS -> settings.getCustomArgs(preset.id) ?: preset.args.joinToString(" ")
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val preset = presets[rowIndex]
            when (columnIndex) {
                COL_ENABLED -> {
                    val enabled = aValue as? Boolean ?: false
                    if (enabled) {
                        selectedPresetIds.add(preset.id)
                    } else {
                        selectedPresetIds.remove(preset.id)
                    }
                }
                COL_COMMAND -> {
                    val command = aValue?.toString()
                    if (!command.isNullOrBlank()) {
                        settings.setCustomCommand(preset.id, command.trim())
                    } else {
                        settings.clearCustomCommand(preset.id)
                    }
                }
                COL_ARGUMENTS -> {
                    val args = aValue?.toString()
                    if (!args.isNullOrBlank()) {
                        settings.setCustomArgs(preset.id, args.trim())
                    } else {
                        settings.clearCustomArgs(preset.id)
                    }
                }
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    private inner class AcpTableCellRenderer : DefaultTableCellRenderer() {

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component =
                super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
            val modelRow = table.convertRowIndexToModel(row)
            val preset = presets[modelRow]
            horizontalAlignment = LEFT
            border = JBUI.Borders.empty(0, 6)
            icon = if (column == COL_NAME) AcpIcons.iconFor(preset.id) else null
            iconTextGap = JBUI.scale(8)
            font = if (column == COL_NAME) {
                JBFont.label().asBold()
            } else {
                JBFont.small()
            }
            foreground = when {
                isSelected -> table.selectionForeground
                column == COL_NAME -> UIUtil.getLabelForeground()
                else -> UIUtil.getContextHelpForeground()
            }
            return component
        }
    }

    private companion object {
        const val COL_ENABLED = 0
        const val COL_NAME = 1
        const val COL_VENDOR = 2
        const val COL_COMMAND = 3
        const val COL_ARGUMENTS = 4
    }
}
