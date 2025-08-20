package com.bodakesatish.ganeshaarties

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bodakesatish.ganeshaarties.databinding.ListItemAartiBinding
import com.google.android.material.color.MaterialColors

import android.graphics.drawable.Animatable // For AVD
import android.graphics.drawable.AnimatedVectorDrawable // For AVD

interface AartiInteractionListener {
    fun onAartiToggled(aarti: AartiItem, isChecked: Boolean)
    fun onStartDrag(viewHolder: RecyclerView.ViewHolder)
}

class AartiAdapter(private val listener: AartiInteractionListener) :
    ListAdapter<AartiItem, AartiAdapter.AartiViewHolder>(AartiDiffCallback()) {

    private var isUserInteractionEnabled = true
    private var currentPlayingItemId: Int? = null
    private var isPlaybackActive: Boolean = false

    @SuppressLint("NotifyDataSetChanged")
    fun setUserInteractionEnabled(enabled: Boolean) {
        if (isUserInteractionEnabled != enabled) {
            isUserInteractionEnabled = enabled
            notifyDataSetChanged()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updatePlaybackVisuals(playingItemId: Int?, isPlaying: Boolean) {
        val visualStateChanged = (currentPlayingItemId != playingItemId) || (this.isPlaybackActive != isPlaying)
        currentPlayingItemId = playingItemId
        this.isPlaybackActive = isPlaying
        if (visualStateChanged) {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AartiViewHolder {
        val binding = ListItemAartiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AartiViewHolder(binding)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: AartiViewHolder, position: Int) {
        val aartiItem = getItem(position)
        holder.bind(
            aartiItem,
            isUserInteractionEnabled,
            isPlaybackActive && aartiItem.id == currentPlayingItemId
        )

        holder.binding.checkboxAarti.isChecked = aartiItem.isChecked
        holder.binding.checkboxAarti.isEnabled = false // Checkbox is visually controlled by item click

        if (isUserInteractionEnabled) {
            holder.itemView.setOnClickListener {
                listener.onAartiToggled(aartiItem, !aartiItem.isChecked)
            }
        } else {
            holder.itemView.setOnClickListener(null)
        }

        holder.binding.imageViewDragHandle.isEnabled = isUserInteractionEnabled
        holder.binding.imageViewDragHandle.alpha = if (isUserInteractionEnabled) 1.0f else 0.5f
        if (isUserInteractionEnabled) {
            holder.binding.imageViewDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    listener.onStartDrag(holder)
                }
                false
            }
        } else {
            holder.binding.imageViewDragHandle.setOnTouchListener(null)
        }
    }

    inner class AartiViewHolder(val binding: ListItemAartiBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(aarti: AartiItem, isUserInteractionAllowed: Boolean, isCurrentlyPlaying: Boolean) {
            binding.textViewAartiTitle.setText(aarti.title)
            itemView.alpha = if (isUserInteractionAllowed || isCurrentlyPlaying) 1.0f else 0.7f

            val context = itemView.context
            val typedValue = TypedValue()
            // Handle AnimatedVectorDrawable for the playing icon
            val playingIconDrawable = binding.imageViewPlayingIcon.drawable

            binding.imageViewPlayingIcon.visibility = if (isCurrentlyPlaying) {
                if (playingIconDrawable is Animatable) { // Check if it's an Animatable (AVD is)
                    (playingIconDrawable as Animatable).start()
                }
                View.VISIBLE
            } else{
                if (playingIconDrawable is Animatable) {
                    if ((playingIconDrawable as Animatable).isRunning) {
                        (playingIconDrawable as Animatable).stop()
                    }
                }
                View.GONE
            }
            binding.checkboxAarti.visibility = if (isCurrentlyPlaying) View.INVISIBLE else View.VISIBLE
            when {
                isCurrentlyPlaying -> {
                    binding.textViewAartiTitle.setTextColor(MaterialColors.getColor(context, R.attr.listItemPlayingText, Color.BLACK))
//                    binding.root.setBackgroundColor(MaterialColors.getColor(context, R.attr.listItemPlayingBackground, Color.LTGRAY))
                }
                aarti.isChecked -> {
                    binding.textViewAartiTitle.setTextColor(MaterialColors.getColor(context, R.attr.listItemSelectorText, Color.BLACK))
//                    binding.root.setBackgroundColor(MaterialColors.getColor(context, R.attr.listItemSelectorBackground, Color.DKGRAY))
                }
                !isUserInteractionEnabled -> {
                    binding.textViewAartiTitle.setTextColor(Color.GRAY)
//                    binding.root.setBackgroundColor(MaterialColors.getColor(context, R.attr.listItemDefaultBackground, Color.TRANSPARENT))
                }
                else -> {
                    // Reset to theme defaults using your custom attributes
                    binding.textViewAartiTitle.setTextColor(MaterialColors.getColor(context, R.attr.listItemDefaultText, Color.BLACK))
//                    binding.root.setBackgroundColor(MaterialColors.getColor(context, R.attr.listItemDefaultBackground, Color.TRANSPARENT))


                }
            }
        }
    }
}

class AartiDiffCallback : DiffUtil.ItemCallback<AartiItem>() {
    override fun areItemsTheSame(oldItem: AartiItem, newItem: AartiItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: AartiItem, newItem: AartiItem): Boolean {
        return oldItem == newItem
    }
}