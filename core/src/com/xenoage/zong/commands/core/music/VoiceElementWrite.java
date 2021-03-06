package com.xenoage.zong.commands.core.music;

import static com.xenoage.utils.base.iterators.ReverseIterator.reverseIt;
import static com.xenoage.utils.kernel.Range.rangeReverse;
import static com.xenoage.zong.core.position.MP.atElement;

import java.util.ArrayList;
import java.util.List;

import com.xenoage.utils.document.command.Command;
import com.xenoage.utils.document.command.Undoability;
import com.xenoage.utils.math.Fraction;
import com.xenoage.zong.core.Score;
import com.xenoage.zong.core.music.Voice;
import com.xenoage.zong.core.music.VoiceElement;
import com.xenoage.zong.core.music.rest.Rest;
import com.xenoage.zong.core.music.time.Time;
import com.xenoage.zong.core.position.MP;
import com.xenoage.zong.util.exceptions.MeasureFullException;


/**
 * Replaces the {@link VoiceElement}s between the given {@link MP}
 * and the given duration (if any) by the given {@link VoiceElement}.
 * If the end position is within an element, that element is removed too.
 * All affected elements (slurs, beams, ...) will be removed.
 * 
 * @author Andreas Wenger
 */
public class VoiceElementWrite
	implements Command {

	//data
	private Voice voice;
	private MP startMP; //beat or element must be given
	private VoiceElement element;
	private boolean checkTimeSignature;
	//backup
	private List<Command> backupCmds = null;
	
	
	/**
	 * Creates a new {@link VoiceElement} command.
	 * @param voice    the affected voice
	 * @param startMP  position where to write the element.<ul>
	 *                 <li>If a beat is given: If there is empty space before this beat,
	 *                 it is filled by rests. If this beat is within an element, not this position
	 *                 but the start position of the element is used.</li>
	 *                 <li>If an element index is given: The element is written
	 *                 at the start beat of the existing element with this index, or, if it does not exist,
	 *                 after the last element in this voice.</li></ul>
	 * @param element  the element to write
	 * @param checkTimeSignature  true, when an exception should be thrown when the element
	 *                 is too long for the current time signature. If this is true, the
	 *                 given voice must be part of a score.
	 */
	public VoiceElementWrite(Voice voice, MP startMP, VoiceElement element, boolean checkTimeSignature) {
		this.voice = voice;
		this.startMP = startMP;
		this.element = element;
		this.checkTimeSignature = checkTimeSignature;
	}


	@Override public void execute() {
		//determine start mp and element index
		Fraction startBeat;
		int elementIndex;
		if (startMP.element != MP.unknown) {
			//start at indexed element
			elementIndex = startMP.element;
			startBeat = voice.getBeat(elementIndex);
		}
		else if (startMP.beat != MP.unknownBeat) {
			//start at given beat
			Fraction filledBeats = voice.getFilledBeats();
			startBeat = startMP.beat;
			Fraction emptySpace = startBeat.sub(filledBeats);
			if (emptySpace.isGreater0()) {
				//add rest between start beat and filled beats, if needed
				executeAndRemember(new VoiceElementWrite(voice, atElement(voice.elements.size()), new Rest(emptySpace), false));
				startBeat = startBeat.add(emptySpace);
				elementIndex = voice.elements.size();
			}
			else {
				elementIndex = voice.getElementIndex(startBeat);
			}
			//update start beat (may be within an element before)
			startBeat = voice.getBeat(elementIndex);
		}
		else {
			throw new IllegalArgumentException("element index or beat must be given");
		}

		//affected range (start and end beat)
		Fraction endBeat = startBeat.add(element.getDuration());

		//optionally check time signature
		if (checkTimeSignature) {
			Score score = voice.getScore();
			if (score == null)
				throw new IllegalStateException("parent score is required");
			Time time = score.getHeader().getTimeAtOrBefore(startMP.getMeasure());
			Fraction duration = time.getType().getMeasureBeats();
			if (duration != null && endBeat.compareTo(duration) > 0) {
				throw new MeasureFullException(startMP, element.getDuration());
			}
		}

		//remove elements within the range
		Fraction posBeat = startBeat;
		int firstRemoveIndex = elementIndex;
		int lastRemoveIndex = -1;
		for (int i = firstRemoveIndex; i < voice.elements.size() && posBeat.compareTo(endBeat) < 0; i++) {
			//we are still not at the end beat. remove element
			VoiceElement e = voice.elements.get(i);
			posBeat = posBeat.add(e.getDuration());
			lastRemoveIndex = i;
		}
		for (int i : rangeReverse(lastRemoveIndex, firstRemoveIndex)) {
			executeAndRemember(new VoiceElementRemove(voice.elements.get(i)));
		}

		//insert new element
		voice.addElement(elementIndex, element);
	}


	@Override public Undoability getUndoability() {
		return Undoability.Undoable;
	}


	@Override public void undo() {
		voice.removeElement(element);
		if (backupCmds != null) {
			for (Command cmd : reverseIt(backupCmds))
				cmd.undo();
		}
	}
	
	
	private void executeAndRemember(Command cmd) {
		if (backupCmds == null)
			backupCmds = new ArrayList<Command>();
		cmd.execute();
		backupCmds.add(cmd);
	}

}
