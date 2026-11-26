package com.yoruc.mangacracker.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Windows' own Save / Open boxes (in place of Android's document picker). */
object Files {
    /** Set by the app: the main window, so the boxes open over it. */
    @Volatile var owner: Frame? = null

    fun save(title: String, name: String, dir: File? = null): File? {
        val d = FileDialog(owner, title, FileDialog.SAVE)
        dir?.let { d.directory = it.absolutePath }
        d.file = name
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }

    /** Windows' folder picker (Swing's chooser in the Windows look). */
    fun folder(title: String, start: File?): File? {
        runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
        val c = javax.swing.JFileChooser(start).apply {
            dialogTitle = title
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (c.showDialog(owner, "Use this folder") == javax.swing.JFileChooser.APPROVE_OPTION) c.selectedFile else null
    }

    fun open(title: String, filter: String = "*.json", dir: File? = null): File? {
        val d = FileDialog(owner, title, FileDialog.LOAD)
        dir?.let { d.directory = it.absolutePath }
        d.file = filter
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }
}
