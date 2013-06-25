/* Copyright (c) 2011 Danish Maritime Authority
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this library.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dma.ais.packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import net.jcip.annotations.NotThreadSafe;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.dma.ais.binary.SixbitException;
import dk.dma.ais.proprietary.IProprietaryTag;
import dk.dma.ais.proprietary.ProprietaryFactory;
import dk.dma.ais.sentence.CommentBlock;
import dk.dma.ais.sentence.Sentence;
import dk.dma.ais.sentence.SentenceException;
import dk.dma.ais.sentence.Vdm;
import dk.dma.ais.transform.AisPacketTaggingTransformer;
import dk.dma.ais.transform.AisPacketTaggingTransformer.Policy;

/**
 * Class to parse lines in a stream containing VDM sentences.
 * The class will deliver packets containing complete VDM and associated
 * comment blocks and proprietary tags.
 */
@NotThreadSafe
public class AisPacketReader {

    /** The logger  */
    private static final Logger LOG = LoggerFactory.getLogger(AisPacketReader.class);

    private static final int SENTENCE_TRACE_COUNT = 20;

    /**
     * The id of the source
     */
    private String sourceId;

    /**
     * A received VDO/VDM
     */
    private Vdm vdm = new Vdm();

    /**
     * List of the raw lines of the AIS packet
     */
    private final List<String> packetLines = new ArrayList<>();

    /**
     * Possible proprietary tags for current VDM
     */
    private final Deque<IProprietaryTag> tags = new ArrayDeque<>();

    private final Deque<String> sentenceTrace = new ArrayDeque<>(SENTENCE_TRACE_COUNT);

    /**
     * Handle a single line. If a complete packet is assembled the package will be returned. Otherwise null is returned.
     * 
     * @param line
     * @return
     * @throws SentenceException
     * @throws SixbitException
     */
    public AisPacket readLine(String line) throws SentenceException {
        return readLine(line, false);
    }

    /**
     * If an out of sequence packet is encountered, the parsing will be restarted at the out of sequence packet
     * @param line
     * @param retry
     * @return
     * @throws SentenceException
     */
    private AisPacket readLine(String line, boolean retry) throws SentenceException {

        if (!retry) {
            // Save line for later trace
            while (sentenceTrace.size() > SENTENCE_TRACE_COUNT) {
                sentenceTrace.removeFirst();
            }
            sentenceTrace.addLast(line);
        }

        // Ignore everything else than sentences
        if (!Sentence.hasSentence(line)) {
            // Gracefully ignore empty lines
            if (line.length() == 0) {
                newVdm();
                return null;
            }
            // Special case is a single comment without sentence
            if (CommentBlock.hasCommentBlock(line)) {
                packetLines.add(line);
                try {
                    vdm.addSingleCommentBlock(line);
                } catch (SentenceException e) {
                    newVdm();
                    throw new SentenceException(e, sentenceTrace);
                }
                return null;
            } else {
                // Non sentence line
                newVdm();
                throw new SentenceException("Non sentence line in stream: " + line, sentenceTrace);
            }
        }

        // Add line to raw packet
        packetLines.add(line);

        // Check if proprietary line
        if (ProprietaryFactory.isProprietaryTag(line)) {
            // Try to parse with one of the registered factories in META-INF/services/dk.dma.ais.proprietary.ProprietaryFactory
            IProprietaryTag tag = ProprietaryFactory.parseTag(line);
            if (tag != null) {
                tags.add(tag);
            }
            return null;
        }

        // Check if VDM. If not the possible current VDM is broken.
        if (!Vdm.isVdm(line)) {
            newVdm();
            return null;
        }

        // Parse VDM
        int result;
        try {
            result = vdm.parse(line);
        } catch (SentenceException e) {
            newVdm();
            // Do a single retry with the current line. The faulty sentence may be the last, not this one.
            if (!retry) {
                LOG.info("Discarding current sentence group. New start: " + e.getMessage());
                return readLine(line, true);
            }
            throw new SentenceException(e, sentenceTrace);
        }

        // If not complete package wait for more
        if (result != 0) {
            return null;
        }

        // Complete package have been read

        // Put proprietary tags on vdm
        if (tags.size() > 0) {
            vdm.setTags(new LinkedList<>(tags));
        }

        // Make packet
        AisPacket packet = new AisPacket(vdm, StringUtils.join(packetLines, "\r\n"));

        // Maybe add source id
        if (sourceId != null) {
            AisPacketTags tagging = new AisPacketTags();
            tagging.setSourceId(sourceId);
            AisPacketTaggingTransformer tranformer = new AisPacketTaggingTransformer(Policy.PREPEND_MISSING, tagging);
            packet = tranformer.transform(packet);
        }

        newVdm();

        return packet;
    }

    public void newVdm() {
        vdm = new Vdm();
        tags.clear();
        packetLines.clear();
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }
}