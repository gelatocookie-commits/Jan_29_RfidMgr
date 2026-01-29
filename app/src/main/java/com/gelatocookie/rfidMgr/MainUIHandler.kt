package com.gelatocookie.rfidMgr

import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class MainUIHandler(private val lifecycleOwner: LifecycleOwner) {
    
    abstract val emcTextView: TextView?
    abstract val statusTextView: TextView?
    abstract val beforeTextView: TextView?
    abstract val afterTextView: TextView?
    abstract val adapter: ArrayAdapter<String>?

    sealed class UIAction {
        data class EmcUpdate(val message: String) : UIAction()
        data class StatusUpdate(val message: String) : UIAction()
        data class BeforeUpdate(val message: String) : UIAction()
        data class AfterUpdate(val message: String) : UIAction()
        object ClearTags : UIAction()
        data class RefreshTagList(val tagDB: Map<String, Int>) : UIAction()
        data class TotalCount(val count: Int) : UIAction()
    }

    fun perform(action: UIAction) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            when (action) {
                is UIAction.EmcUpdate -> emcTextView?.text = action.message
                is UIAction.StatusUpdate -> statusTextView?.text = action.message
                is UIAction.BeforeUpdate -> {
                    val currentText = beforeTextView?.text?.toString() ?: ""
                    beforeTextView?.text = if (currentText.isEmpty()) action.message else "$currentText\n${action.message}"
                }
                is UIAction.AfterUpdate -> {
                    val currentText = afterTextView?.text?.toString() ?: ""
                    afterTextView?.text = if (currentText.isEmpty()) action.message else "$currentText\n${action.message}"
                }
                is UIAction.ClearTags -> adapter?.clear()
                is UIAction.TotalCount -> statusTextView?.text = "Total Tags: ${action.count}"
                is UIAction.RefreshTagList -> {
                    adapter?.clear()
                    action.tagDB.forEach { (epc, count) ->
                        adapter?.add("EPC: $epc | Count: $count")
                    }
                    adapter?.notifyDataSetChanged()
                }
            }
        }
    }
}
