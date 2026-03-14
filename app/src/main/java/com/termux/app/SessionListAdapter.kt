package com.termux.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.termux.R

class SessionListAdapter(
    private var sessions: MutableList<SessionMeta>,
    private val onSessionClick: (SessionMeta) -> Unit,
    private val onSessionLongClick: (SessionMeta) -> Boolean
) : RecyclerView.Adapter<SessionListAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = sessions[position]
        holder.bind(s)
        holder.itemView.setOnClickListener { onSessionClick(s) }
        holder.itemView.setOnLongClickListener { onSessionLongClick(s) }
    }

    override fun getItemCount(): Int = sessions.size

    fun setItems(newList: List<SessionMeta>) {
        sessions.clear()
        sessions.addAll(newList)
        notifyDataSetChanged()
    }

    fun removeSession(id: String) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx >= 0) {
            sessions.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        fun bind(meta: SessionMeta) {
            itemView.findViewById<TextView>(R.id.tvSessionTitle)?.text = meta.title.ifBlank { "新会话" }
            itemView.findViewById<TextView>(R.id.tvSessionTime)?.text = formatSessionTime(meta.updatedAt)
        }
    }
}
