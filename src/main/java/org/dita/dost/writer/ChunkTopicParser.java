/*
 * This file is part of the DITA Open Toolkit project.
 * See the accompanying license.txt file for applicable licenses.
 */

/*
 * (c) Copyright IBM Corp. 2007 All Rights Reserved.
 */
package org.dita.dost.writer;

import static java.util.Arrays.*;
import static org.dita.dost.module.GenMapAndTopicListModule.*;
import static org.dita.dost.reader.ChunkMapReader.*;
import static org.dita.dost.util.Constants.*;
import static org.dita.dost.util.FileUtils.*;
import static org.dita.dost.util.URLUtils.*;
import static org.dita.dost.util.XMLUtils.*;
import static org.dita.dost.writer.DitaWriter.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.util.*;

import javax.xml.parsers.DocumentBuilder;

import org.dita.dost.exception.DITAOTException;
import org.dita.dost.exception.DITAOTXMLErrorHandler;
import org.dita.dost.log.MessageUtils;
import org.dita.dost.module.ChunkModule.ChunkFilenameGenerator;
import org.dita.dost.util.DitaClass;
import org.dita.dost.util.StringUtils;
import org.dita.dost.util.TopicIdParser;
import org.w3c.dom.*;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;

/**
 * ChunkTopicParser class, writing chunking content into relative topic files
 * and then update list. Not reusable and not thread-safe.
 * 
 * <p>
 * TODO: Refactor to be a SAX filter.
 * </p>
 */
public final class ChunkTopicParser extends AbstractXMLWriter {

    private static final String ATTR_CHUNK_VALUE_SELECT_BRANCH = "select-branch";
    private static final String ATTR_CHUNK_VALUE_TO_CONTENT = "to-content";
    private static final String ATTR_CHUNK_VALUE_SELECT_TOPIC = "select-topic";
    private static final String ATTR_CHUNK_VALUE_SELECT_DOCUMENT = "select-document";
    private static final String ditaarchNSValue = "http://dita.oasis-open.org/architecture/2005/";

    /** Keys and values are absolute chimera paths, i.e. systems paths with fragments */
    private LinkedHashMap<String, String> changeTable = null;
    /** Keys and values are absolute chimera paths, i.e. systems paths with fragments */
    private Map<String, String> conflictTable = null;

    private Element rootTopicref = null;

    private Element topicDoc = null;

    private boolean separate = false;
    /** Input file's parent directory */
    private File filePath = null;

    private File currentParsingFile = null;
    private File outputFile = null;
    private final Stack<File> outputFileNameStack = new Stack<File>();

    private String targetTopicId = null;

    private String selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
    // flag whether output the nested nodes
    private boolean include = false;
    private boolean skip = false;

    private int includelevel = 0;
    private int skipLevel = 0;

    private final Set<String> topicSpecSet = new HashSet<String>(16);

    private boolean startFromFirstTopic = false;

    private final XMLReader reader;
    private Writer output = null;

    private final Stack<Writer> outputStack = new Stack<Writer>();
    private final Stack<Element> stubStack = new Stack<Element>();

    // stub is used as the anchor to mark where to insert generated child
    // topicref inside current topicref
    private Element stub = null;

    // siblingStub is similar to stub. The only different is it is used to
    // insert generated topicref sibling to current topicref
    private Element siblingStub = null;

    private Set<String> topicID = new HashSet<String>();

    private final Set<String> copyto = new HashSet<String>();

    private final Set<String> copytoSource = new HashSet<String>();

    private final Map<File, File> copytotarget2source = new HashMap<File, File>();

    private Map<String, String> currentParsingFileTopicIDChangeTable;

    private ChunkFilenameGenerator chunkFilenameGenerator;

    /**
     * Constructor.
     */
    public ChunkTopicParser() {
        super();
        try {
            reader = getXMLReader();
            reader.setContentHandler(this);
            reader.setFeature(FEATURE_NAMESPACE_PREFIX, true);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to initialize XML parser: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(final File filename) throws DITAOTException {
        // pass map's directory path
        filePath = filename;
        try {
            if (separate) {
                separateChunk(rootTopicref);
            } else {
                output = new StringWriter();
                processChunk(rootTopicref, null);
            }
            if (!copyto.isEmpty()) {
                updateList();
            }
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (final IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Set up the class.
     *
     * @param changeTable changeTable
     * @param conflictTable conflictTable
     * @param refFileSet refFileSet
     * @param rootTopicref chunking topicref
     * @param separate separate
     */
    public void setup(final LinkedHashMap<String, String> changeTable, final Map<String, String> conflictTable,
                      final Set<String> refFileSet, final Element rootTopicref, final boolean separate,
                      final ChunkFilenameGenerator chunkFilenameGenerator) {
        this.changeTable = changeTable;
        this.rootTopicref = rootTopicref;
        this.separate = separate;
        this.conflictTable = conflictTable;
        this.chunkFilenameGenerator = chunkFilenameGenerator;
    }

    // Filter methods

    @Override
    public void characters(final char[] ch, final int start, final int length) throws SAXException {
        if (include) {
            try {
                output.write(escapeXML(ch, start, length));
            } catch (final IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void endDocument() throws SAXException {
        include = false;
        skip = false;
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        if (skip && skipLevel > 0) {
            skipLevel--;
        } else if (skip) {
            include = true;
            skip = false;
            skipLevel = 0;
        }

        if (include) {
            try {
                includelevel--;
                if (includelevel >= 0) {
                    // prevent adding </dita> into output
                    writeEndElement(output, qName);
                }
                if (includelevel == 0 && !ATTR_CHUNK_VALUE_SELECT_DOCUMENT.equals(selectMethod)) {
                    include = false;
                }
                if (topicSpecSet.contains(qName) && separate && !outputStack.isEmpty()) {
                    // if it is end of topic and separate is true
                    try {
                        output.close();
                    } catch (final IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                    output = outputStack.pop();
                    outputFile = outputFileNameStack.pop();
                    stub.getParentNode().removeChild(stub);
                    stub = stubStack.pop();
                }
            } catch (final IOException e) {
                logger.error(e.getMessage(), e);
            }
        }

    }

    @Override
    public void endPrefixMapping(final String prefix) throws SAXException {
        super.endPrefixMapping(prefix);
    }

    @Override
    public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
        if (include) {
            try {
                output.write(ch, start, length);
            } catch (final IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void processingInstruction(final String target, final String data) throws SAXException {
        if (include
                || PI_WORKDIR_TARGET.equals(target)
                || PI_WORKDIR_TARGET_URI.equals(target)
                || PI_PATH2PROJ_TARGET.equals(target)) {
            try {
                writeProcessingInstruction(output, target, data);
            } catch (final IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void startDocument() throws SAXException {
        // difference between to-content & select-topic
        if (ATTR_CHUNK_VALUE_SELECT_DOCUMENT.equals(selectMethod)) {
            // currentParsingFile can never equal outputFile except when
            // chunk="to-content"
            // is set at map level
            if ((currentParsingFile).equals(outputFile)) {
                // if current file serves as root of new chunk
                // include will be set to true in startDocument()
                // in order to copy PIs and <dita> element
                // otherwise, if current file is copied to other file
                // do not copy PIs and <dita>element
                include = true;
                skip = false;
                skipLevel = 0;
            } else {
                include = false;
                startFromFirstTopic = true;
                skip = false;
                skipLevel = 0;
            }
        }
    }

    @Override
    public void startElement(final String uri, final String localName, final String qName, final Attributes atts)
            throws SAXException {
        final String classValue = atts.getValue(ATTRIBUTE_NAME_CLASS);
        final String idValue = atts.getValue(ATTRIBUTE_NAME_ID);

        if (skip && skipLevel > 0) {
            skipLevel++;
        }

        try {
            if (TOPIC_TOPIC.matches(classValue)) {
                topicSpecSet.add(qName);
                final String id = atts.getValue(ATTRIBUTE_NAME_ID);
                // search node by id.
                final Element topic = searchForNode(topicDoc, id, ATTRIBUTE_NAME_ID, TOPIC_TOPIC);

                // only by topic
                if (separate && include && !ATTR_CHUNK_VALUE_SELECT_TOPIC.equals(selectMethod)) {
                    // chunk="by-topic" and next topic element found
                    outputStack.push(output);
                    outputFileNameStack.push(outputFile);

                    // need generate new file based on new topic id
                    File newFileName = resolve(filePath, idValue + FILE_EXTENSION_DITA);
                    if (idValue == null || newFileName.exists()) {
                        final File t = newFileName;
                        newFileName = resolve(filePath, generateFilename());
                        conflictTable.put(newFileName.getPath(), t.getPath());
                    }
                    outputFile = newFileName;
                    output = new OutputStreamWriter(new FileOutputStream(newFileName), UTF8);
                    // write xml header and workdir PI to the new generated file
                    writeStartDocument(output);
                    if (!OS_NAME.toLowerCase().contains(OS_NAME_WINDOWS)) {
                        writeProcessingInstruction(output, PI_WORKDIR_TARGET, filePath.getAbsolutePath());
                    } else {
                        writeProcessingInstruction(output, PI_WORKDIR_TARGET, UNIX_SEPARATOR + filePath);
                    }
                    writeProcessingInstruction(output, PI_WORKDIR_TARGET_URI, filePath.toURI().toString());
                    changeTable.put(newFileName.getPath(), newFileName.getPath());
                    if (idValue != null) {
                        changeTable.put(setFragment(currentParsingFile.getPath(), idValue), setFragment(newFileName.getPath(), idValue));
                    } else {
                        changeTable.put(currentParsingFile.getPath(), newFileName.getPath());
                    }
                    // create a new child element
                    // in separate case topicref is equals to parameter
                    // element in separateChunk(Element element)
                    final Element newTopicref = rootTopicref.getOwnerDocument().createElement(MAP_TOPICREF.localName);
                    newTopicref.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_TOPICREF.toString());
                    newTopicref.setAttribute(ATTRIBUTE_NAME_XTRF, ATTR_XTRF_VALUE_GENERATED);
                    newTopicref.setAttribute(ATTRIBUTE_NAME_HREF, toURI(getRelativePath(new File(filePath, FILE_NAME_STUB_DITAMAP), newFileName)).toString());

                    final Element topicmeta = createTopicMeta(topic);
                    newTopicref.appendChild(topicmeta);

                    if (stub != null) {
                        if (includelevel == 0 && siblingStub != null) {
                            // if it is the following sibling topic to the first
                            // topic in ditabase
                            // The first topic will not enter the logic at here
                            // because when meeting
                            // with first topic in ditabase, the include value
                            // is false
                            siblingStub.getParentNode().insertBefore(newTopicref, siblingStub);

                        } else {
                            stub.getParentNode().insertBefore(newTopicref, stub);
                        }
                        stubStack.push(stub);
                        stub = (Element) stub.cloneNode(false);
                        newTopicref.appendChild(stub);
                    }
                }
                if (include && ATTR_CHUNK_VALUE_SELECT_TOPIC.equals(selectMethod)) {
                    // if select method is "select-topic" and
                    // current topic is the nested topic in
                    // target topic-->skip it.
                    include = false;
                    skipLevel = 1;
                    skip = true;
                } else if (include) {
                    // if select method is "select-document" or "select-branch"
                    // and current topic is the nested topic in target topic.
                    // if file name has been changed, add an entry in
                    // changeTable
                    if (!currentParsingFile.equals(outputFile)) {
                        if (idValue != null) {
                            changeTable.put(setFragment(currentParsingFile.getPath(), idValue), setFragment(outputFile.getPath(), idValue));
                        } else {
                            changeTable.put(currentParsingFile.getPath(), outputFile.getPath());
                        }
                    }
                } else if (skip) {
                    skipLevel = 1;
                } else if (!include && idValue != null && (idValue.equals(targetTopicId) || startFromFirstTopic)) {
                    // if the target topic has not been found and
                    // current topic is the target topic
                    include = true;
                    includelevel = 0;
                    skip = false;
                    skipLevel = 0;
                    startFromFirstTopic = false;
                    if (!currentParsingFile.equals(outputFile)) {
                        changeTable.put(setFragment(currentParsingFile.getPath(), idValue), setFragment(outputFile.getPath(), idValue));
                    }
                }
            }

            if (include) {
                includelevel++;
                final AttributesImpl resAtts = new AttributesImpl(atts);
                for (int i = 0; i < resAtts.getLength(); i++) {
                    final String attrName = resAtts.getQName(i);
                    String attrValue = resAtts.getValue(i);

                    if (ATTRIBUTE_NAME_ID.equals(attrName)) {
                        if (TOPIC_TOPIC.matches(classValue)) {
                            // change topic @id if there are conflicts.
                            if (topicID.contains(attrValue)) {
                                final String oldAttrValue = attrValue;
                                attrValue = chunkFilenameGenerator.generateID();
                                topicID.add(attrValue);

                                String tmpVal = changeTable.get(setFragment(currentParsingFile.getPath(), idValue));
                                if (tmpVal != null && tmpVal.equalsIgnoreCase(setFragment(outputFile.getPath(), idValue))) {
                                    changeTable.put(setFragment(currentParsingFile.getPath(), idValue), setFragment(outputFile.getPath(), attrValue));
                                }

                                tmpVal = changeTable.get(currentParsingFile.getPath());
                                if (tmpVal != null && tmpVal.equalsIgnoreCase(setFragment(outputFile.getPath(), idValue))) {
                                    changeTable.put(currentParsingFile.getPath(), setFragment(outputFile.getPath(), attrValue));
                                }
                                currentParsingFileTopicIDChangeTable.put(oldAttrValue, attrValue);
                            } else {
                                topicID.add(attrValue);
                            }
                        }
                    } else if (ATTRIBUTE_NAME_HREF.equals(attrName)) {
                        // update @href value
                        if (checkHREF(resAtts)) {
                            // if current @href value needs to be updated
                            String relative = getRelativeUnixPath(outputFile, currentParsingFile.getPath());
                            if (conflictTable.containsKey(outputFile.getPath())) {
                                final String realoutputfile = conflictTable.get(outputFile.getPath());
                                relative = getRelativeUnixPath(realoutputfile, currentParsingFile.getPath());
                            }
                            if (attrValue.startsWith(SHARP)) {
                                // if @href refers to a location inside current
                                // parsing file
                                // update @href to point back to current file
                                // if the location is moved to chunk, @href will
                                // be update again
                                // to the new location.
                                attrValue = relative + attrValue;
                            } else if (relative.contains(SLASH)) {
                                // if new file is not under the same directory
                                // with current file
                                // add path information to the @href value
                                relative = relative.substring(0, relative.lastIndexOf(SLASH));
                                attrValue = resolveTopic(relative, attrValue);
                            }
                        }
                    }
                    resAtts.setValue(i, attrValue);
                }

                if (TOPIC_TOPIC.matches(classValue) && resAtts.getValue("xmlns:ditaarch") == null) {
                    // if there is none declaration for ditaarch namespace,
                    // processor need to add it
                    addOrSetAttribute(resAtts, ATTRIBUTE_NAMESPACE_PREFIX_DITAARCHVERSION, ditaarchNSValue);
                }

                writeStartElement(output, qName, resAtts);
            }
        } catch (final IOException e) {
            logger.error(e.getMessage(), e);
        }
    }


    private void updateList() {
        try {
            // XXX: This may have to use new
            // File(resolve(filePath,FILE_NAME_DITA_LIST_XML)).getParent()
            final Map<File, File> copytotarget2sourcemaplist = job.getCopytoMap();
            copytotarget2source.putAll(copytotarget2sourcemaplist);
            for (final String file : copytoSource) {
                job.getOrCreateFileInfo(toURI(file)).isCopyToSource = true;
            }
            job.setCopytoMap(copytotarget2source);
            job.write();
        } catch (final IOException e) {
            logger.error(e.getMessage(), e);
        } finally {

        }
    }

    private void separateChunk(final Element topicref) {
        final String hrefValue = getValue(topicref, ATTRIBUTE_NAME_HREF);
        final String copytoValue = getValue(topicref, ATTRIBUTE_NAME_COPY_TO);
        final String scopeValue = getCascadeValue(topicref, ATTRIBUTE_NAME_SCOPE);
        // Chimera path, has fragment
        String parseFilePath;
        Writer tempOutput = null;
        final Collection<String> chunkValue = split(getValue(topicref,ATTRIBUTE_NAME_CHUNK));
        final String processRoleValue = getCascadeValue(topicref, ATTRIBUTE_NAME_PROCESSING_ROLE);
        boolean dotchunk = false;

        if (copytoValue != null && !chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)) {
            if (hrefValue != null && getFragment(hrefValue) != null) {
                parseFilePath = setFragment(copytoValue, getFragment(hrefValue));
            } else {
                parseFilePath = copytoValue;
            }
        } else {
            parseFilePath = hrefValue;
        }

        // if @copy-to is processed in chunk module, the list file needs to be
        // updated.
        // Because @copy-to should be included in fulltopiclist, and the source
        // of coyy-to should be excluded in fulltopiclist.
        if (copytoValue != null && chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)) {
            copyto.add(copytoValue);
            if (hrefValue != null && getFragment(hrefValue) != null) {
                copytoSource.add(stripFragment(hrefValue));
                copytotarget2source.put(toFile(copytoValue), toFile(stripFragment(hrefValue)));
            } else {
                copytoSource.add(hrefValue);
                copytotarget2source.put(toFile(copytoValue), toFile(hrefValue));
            }
        }
        try {
            if (parseFilePath != null && !ATTR_SCOPE_VALUE_EXTERNAL.equals(scopeValue)
                    && !ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY.equals(processRoleValue)) {
                // if the path to target file make sense
                currentParsingFile = resolve(filePath, parseFilePath);
                File outputFileName;
                /*
                 * FIXME: we have code flaws here, references in ditamap need to
                 * be updated to new created file.
                 */
                String id = null;
                String firstTopicID = null;
                if (getFragment(parseFilePath) != null) {
                    id = getFragment(parseFilePath);
                    if (chunkValue.contains(ATTR_CHUNK_VALUE_SELECT_BRANCH)) {
                        outputFileName = resolve(filePath, id + FILE_EXTENSION_DITA);
                        targetTopicId = id;
                        startFromFirstTopic = false;
                        selectMethod = ATTR_CHUNK_VALUE_SELECT_BRANCH;
                    } else if (chunkValue.contains(ATTR_CHUNK_VALUE_SELECT_DOCUMENT)) {
                        firstTopicID = getFirstTopicId(resolve(filePath, parseFilePath).getPath());

                        topicDoc = getTopicDoc(resolve(filePath, parseFilePath).getPath());

                        if (firstTopicID != null) {
                            outputFileName = resolve(filePath, firstTopicID + FILE_EXTENSION_DITA);
                            targetTopicId = firstTopicID;
                        } else {
                            outputFileName = new File(currentParsingFile.getPath() + FILE_EXTENSION_CHUNK);
                            dotchunk = true;
                            targetTopicId = null;
                        }
                        selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
                    } else {
                        outputFileName = resolve(filePath, id + FILE_EXTENSION_DITA);
                        targetTopicId = id;
                        startFromFirstTopic = false;
                        selectMethod = ATTR_CHUNK_VALUE_SELECT_TOPIC;
                    }
                } else {
                    firstTopicID = getFirstTopicId(resolve(filePath, parseFilePath).getPath());

                    topicDoc = getTopicDoc(resolve(filePath, parseFilePath).getPath());

                    if (firstTopicID != null) {
                        outputFileName = resolve(filePath, firstTopicID + FILE_EXTENSION_DITA);
                        targetTopicId = firstTopicID;
                    } else {
                        outputFileName = new File(currentParsingFile.getPath() + FILE_EXTENSION_CHUNK);
                        dotchunk = true;
                        targetTopicId = null;
                    }
                    selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
                }
                if (copytoValue != null) {
                    // use @copy-to value as the new file name
                    outputFileName = resolve(filePath, copytoValue);
                }

                if (outputFileName.exists()) {
                    final File t = outputFileName;
                    outputFileName = resolve(filePath, generateFilename());
                    conflictTable.put(outputFileName.getPath(), t.getPath());
                    dotchunk = false;
                }
                tempOutput = output;
                output = new OutputStreamWriter(new FileOutputStream(outputFileName), UTF8);
                outputFile = outputFileName;
                if (!dotchunk) {
                    changeTable.put(resolveTopic(filePath, parseFilePath),
                            setFragment(outputFileName.getPath(), id));
                    // new generated file
                    changeTable.put(outputFileName.getPath(), outputFileName.getPath());
                }
                // change the href value
                if (firstTopicID == null) {
                    topicref.setAttribute(ATTRIBUTE_NAME_HREF,
                            setFragment(toURI(getRelativePath(new File(filePath, FILE_NAME_STUB_DITAMAP), outputFileName)), id).toString());
                } else {
                    topicref.setAttribute(ATTRIBUTE_NAME_HREF,
                            setFragment(toURI(getRelativePath(new File(filePath, FILE_NAME_STUB_DITAMAP), outputFileName)), firstTopicID).toString());
                }
                include = false;
                // just a mark?
                stub = topicref.getOwnerDocument().createElement(ELEMENT_STUB);
                siblingStub = topicref.getOwnerDocument().createElement(ELEMENT_STUB);
                // <element>
                // <stub/>
                // ...
                // </element>
                // <siblingstub/>
                // ...
                // Place stub
                if (topicref.hasChildNodes()) {
                    final NodeList list = topicref.getElementsByTagName(MAP_TOPICMETA.localName);
                    if (list.getLength() > 0) {
                        final Node node = list.item(0);
                        final Node nextSibling = node.getNextSibling();
                        // no sibling so node is the last child
                        if (nextSibling == null) {
                            node.getParentNode().appendChild(stub);
                        } else {
                            // has sibling node
                            node.getParentNode().insertBefore(stub, nextSibling);
                        }
                    } else {
                        // no topicmeta tag.
                        topicref.insertBefore(stub, topicref.getFirstChild());
                    }

                    // element.insertBefore(stub,element.getFirstChild());
                } else {
                    topicref.appendChild(stub);
                }

                // Place siblingStub
                if (topicref.getNextSibling() != null) {
                    topicref.getParentNode().insertBefore(siblingStub, topicref.getNextSibling());
                } else {
                    topicref.getParentNode().appendChild(siblingStub);
                }

                reader.setErrorHandler(new DITAOTXMLErrorHandler(currentParsingFile.getPath(), logger));
                reader.parse(currentParsingFile.toURI().toString());
                output.flush();

                // remove stub and siblingStub
                stub.getParentNode().removeChild(stub);
                siblingStub.getParentNode().removeChild(siblingStub);
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            try {
                if (output != null) {
                    output.close();
                    if (dotchunk && !currentParsingFile.delete()) {
                        logger.error(MessageUtils.getInstance()
                                .getMessage("DOTJ009E", currentParsingFile.getPath(), outputFile.getPath()).toString());
                    }
                    if (dotchunk && !outputFile.renameTo(currentParsingFile)) {
                        logger.error(MessageUtils.getInstance()
                                .getMessage("DOTJ009E", currentParsingFile.getPath(), outputFile.getPath()).toString());
                    }
                }
                output = tempOutput;
            } catch (final Exception ex) {
                logger.error(ex.getMessage(), ex);
            }
        }
    }

    /**
     * Generate file name.
     * 
     * @return generated file name
     */
    private String generateFilename() {
        return chunkFilenameGenerator.generateFilename("Chunk", FILE_EXTENSION_DITA);
    }

    private void processChunk(final Element topicref, final File outputFile) {
        final String hrefValue = getValue(topicref, ATTRIBUTE_NAME_HREF);
        final Collection<String> chunkValue = split(getValue(topicref, ATTRIBUTE_NAME_CHUNK));
        final String copytoValue = getValue(topicref, ATTRIBUTE_NAME_COPY_TO);
        final String scopeValue = getCascadeValue(topicref, ATTRIBUTE_NAME_SCOPE);
        final String classValue = getValue(topicref, ATTRIBUTE_NAME_CLASS);
        final String processRoleValue = getCascadeValue(topicref, ATTRIBUTE_NAME_PROCESSING_ROLE);
        final String id = getValue(topicref, ATTRIBUTE_NAME_ID);
        final String navtitle = getValue(topicref, ATTRIBUTE_NAME_NAVTITLE);

        // file which will be parsed
        String parseFilePath;
        File outputFileName = outputFile;
        Writer tempWriter = new StringWriter();
        Set<String> tempTopicID = new HashSet<String>();

        targetTopicId = null;
        selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
        include = false;

        boolean needWriteDitaTag = true;

        try {
            // Get target chunk file name
            if (copytoValue != null && !chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)) {
                if (getFragment(hrefValue) != null) {
                    parseFilePath = setFragment(copytoValue, getFragment(hrefValue));
                } else {
                    parseFilePath = copytoValue;
                }
            } else {
                parseFilePath = hrefValue;
            }

            // if @copy-to is processed in chunk module, the list file needs to
            // be updated.
            // Because @copy-to should be included in fulltopiclist, and the
            // source of coyy-to should be excluded in fulltopiclist.
            if (copytoValue != null && chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)
                    && hrefValue != null) {
                copyto.add(copytoValue);
                if (getFragment(hrefValue) != null) {
                    copytoSource.add(stripFragment(hrefValue));
                    copytotarget2source.put(toFile(copytoValue), toFile(stripFragment(hrefValue)));
                } else {
                    copytoSource.add(hrefValue);
                    copytotarget2source.put(toFile(copytoValue), toFile(hrefValue));
                }
            }

            if (!MAPGROUP_D_TOPICGROUP.matches(classValue) && parseFilePath != null
                    && !ATTR_SCOPE_VALUE_EXTERNAL.equals(scopeValue)) {
                // now the path to target file make sense
                if (chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)) {
                    // if current element contains "to-content" in chunk
                    // attribute
                    // we need to create new buffer and flush the buffer to
                    // file
                    // after processing is finished
                    tempWriter = output;
                    tempTopicID = topicID;
                    output = new StringWriter();
                    topicID = new HashSet<String>();
                    if (MAP_MAP.matches(classValue)) {
                        // Very special case, we have a map element with
                        // href value.
                        // This is a map that needs to be chunked to
                        // content.
                        // No need to parse any file, just generate a stub
                        // output.
                        outputFileName = resolve(filePath, parseFilePath);
                        needWriteDitaTag = false;
                    } else if (copytoValue != null) {
                        // use @copy-to value as the new file name
                        outputFileName = resolve(filePath, copytoValue);
                    } else if (hrefValue != null) {
                        // try to use href value as the new file name
                        if (chunkValue.contains(ATTR_CHUNK_VALUE_SELECT_TOPIC)
                                || chunkValue.contains(ATTR_CHUNK_VALUE_SELECT_BRANCH)) {
                            if (getFragment(hrefValue) != null) {
                                // if we have an ID here, use it.
                                outputFileName = resolve(filePath, getFragment(hrefValue) + FILE_EXTENSION_DITA);
                            } else {
                                // Find the first topic id in target file if
                                // any.
                                final String firstTopic = getFirstTopicId(resolve(filePath, hrefValue).getPath());
                                if (firstTopic != null) {
                                    outputFileName = resolve(filePath, firstTopic + FILE_EXTENSION_DITA);
                                } else {
                                    outputFileName = resolve(filePath, hrefValue);
                                }
                            }
                        } else {
                            // otherwise, use the href value instead
                            outputFileName = resolve(filePath, hrefValue);
                        }
                    } else {
                        // use randomly generated file name
                        outputFileName = resolve(filePath, generateFilename());
                    }

                    // Check if there is any conflict
                    if (outputFileName.exists() && !MAP_MAP.matches(classValue)) {
                        final File t = outputFileName;
                        outputFileName = resolve(filePath, generateFilename());
                        conflictTable.put(outputFileName.getPath(), t.getPath());
                    }
                    // add newly generated file to changTable
                    // the new entry in changeTable has same key and value
                    // in order to indicate it is a newly generated file
                    changeTable.put(outputFileName.getPath(), outputFileName.getPath());
                }
                // "by-topic" couldn't reach here
                this.outputFile = outputFileName;

                final String path = resolveTopic(filePath, parseFilePath);
                // FIXME: Should be URI
                String newpath;
                if (getFragment(path) != null) {
                    newpath = setFragment(outputFileName.getPath(), getFragment(path));
                } else {
                    final String firstTopicID = getFirstTopicId(path);
                    if (firstTopicID != null) {
                        newpath = setFragment(outputFileName.getPath(), firstTopicID);
                    } else {
                        newpath = outputFileName.getPath();
                    }
                }
                // add file name changes to changeTable, this will be
                // used in
                // TopicRefWriter's updateHref method, very important!!!
                changeTable.put(path, newpath);
                // update current element's @href value
                topicref.setAttribute(ATTRIBUTE_NAME_HREF, getRelativeUnixPath(filePath + UNIX_SEPARATOR + FILE_NAME_STUB_DITAMAP, newpath));

                if (getFragment(parseFilePath) != null) {
                    targetTopicId = getFragment(parseFilePath);
                }

                final String s = getChunkByToken(chunkValue, "select-", null);
                if (s != null) {
                    selectMethod = s;
                    // if the current topic href referred to a entire
                    // topic file,it will be handled in "document" level.
                    if (targetTopicId == null) {
                        selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
                    }
                }
                final File tempPath = currentParsingFile;
                currentParsingFile = resolve(filePath, parseFilePath);

                if (!ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY.equals(processRoleValue)) {
                    currentParsingFileTopicIDChangeTable = new HashMap<String, String>();
                    // TODO recursive point
                    reader.parse(currentParsingFile.toURI().toString());
                    if (currentParsingFileTopicIDChangeTable.size() > 0) {
                        final URI href = toURI(topicref.getAttribute(ATTRIBUTE_NAME_HREF));
                        final String pathtoElem = href.getFragment() != null
                                ? href.getFragment()
                                : "";
                        final String old_elementid = pathtoElem.contains(SLASH)
                                ? pathtoElem.substring(0, pathtoElem.indexOf(SLASH))
                                : pathtoElem;
                        if (!old_elementid.isEmpty()) {
                            final String new_elementid = currentParsingFileTopicIDChangeTable.get(old_elementid);
                            if (new_elementid != null && !new_elementid.isEmpty()) {
                                topicref.setAttribute(ATTRIBUTE_NAME_HREF, setFragment(href, new_elementid).toString());
                            }
                        }
                    }
                    currentParsingFileTopicIDChangeTable = null;
                }
                // restore the currentParsingFile
                currentParsingFile = tempPath;
            }

            // use @copy-to value(dita spec v1.2)
            if (outputFileName == null) {
                if (copytoValue != null) {
                    outputFileName = resolve(filePath, copytoValue);
                    // use id value
                } else if (id != null) {
                    outputFileName = resolve(filePath, id + FILE_EXTENSION_DITA);
                } else {
                    // use randomly generated file name
                    outputFileName = resolve(filePath, generateFilename());
                    // Check if there is any conflict
                    if (outputFileName.exists() && !MAP_MAP.matches(classValue)) {
                        final File t = outputFileName;
                        outputFileName = resolve(filePath, generateFilename());
                        conflictTable.put(outputFileName.getPath(), t.getPath());
                    }
                }

                // if topicref has child node or topicref has @navtitle
                if (topicref.hasChildNodes() || navtitle != null) {
                    String navtitleValue = getChildElementValueOfTopicmeta(topicref, TOPIC_NAVTITLE);
                    if (navtitleValue == null) {
                        navtitleValue = navtitle;
                    }
                    String shortDescValue = getChildElementValueOfTopicmeta(topicref, MAP_SHORTDESC);

                    // add newly generated file to changTable
                    // the new entry in changeTable has same key and value
                    // in order to indicate it is a newly generated file
                    changeTable.put(outputFileName.getPath(), outputFileName.getPath());
                    // update current element's @href value
                    // create a title-only topic when there is a title
                    if (navtitleValue != null) {
                        topicref.setAttribute(ATTRIBUTE_NAME_HREF, toURI(getRelativePath(new File(filePath,
                                FILE_NAME_STUB_DITAMAP), outputFileName)).toString());
                        // manually create a new topic chunk
                        final StringBuilder buffer = new StringBuilder();
                        buffer.append("<topic id=\"topic\" class=\"- topic/topic \">")
                                .append("<title class=\"- topic/title \">").append(navtitleValue)
                                .append("</title>");
                        // has shortdesc value
                        if (shortDescValue != null) {
                            buffer.append("<shortdesc class=\"- topic/shortdesc \">").append(shortDescValue)
                                    .append("</shortdesc>");
                        }
                        buffer.append("</topic>");

                        final StringReader rder = new StringReader(buffer.toString());
                        final InputSource source = new InputSource(rder);

                        // for recursive
                        final File tempPath = currentParsingFile;
                        currentParsingFile = outputFileName;
                        // insert not append the nested topic
                        parseFilePath = outputFileName.getPath();
                        // create chunk
                        reader.parse(source);
                        // restore the currentParsingFile
                        currentParsingFile = tempPath;
                    }
                }
            }

            if (topicref.hasChildNodes()) {
                // if current element has child nodes and chunk results for
                // this element has value
                // which means current element makes sense for chunk action.
                final StringWriter temp = (StringWriter) output;
                output = new StringWriter();
                final NodeList children = topicref.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    final Node current = children.item(i);
                    if (MAP_TOPICREF.matches(current)) {
                        processChunk((Element) current, outputFileName);
                    }
                }

                // merge results
                final StringBuffer parentResult = temp.getBuffer();
                // Skip empty parents and @processing-role='resource-only'
                // entries.
                if (parentResult.length() > 0
                        && parseFilePath != null
                        && !ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY.equals(processRoleValue)) {
                    int insertpoint = parentResult.lastIndexOf("</");
                    final int end = parentResult.indexOf(">", insertpoint);

                    if (insertpoint == -1 || end == -1) {
                        logger.error(MessageUtils.getInstance().getMessage("DOTJ033E", hrefValue).toString());
                    } else {
                        if (ELEMENT_NAME_DITA.equals(parentResult.substring(insertpoint, end).trim())) {
                            insertpoint = parentResult.lastIndexOf("</", insertpoint - 1);
                        }
                        parentResult.insert(insertpoint, ((StringWriter) output).getBuffer());
                    }
                } else {
                    parentResult.append(((StringWriter) output).getBuffer());
                }
                // restore back to parent's output this is a different temp
                output = temp;
            }

            if (chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)) {
                // flush the buffer to file after processing is finished
                // and restore back original output

                final FileOutputStream fileOutput = new FileOutputStream(outputFileName);
                OutputStreamWriter ditaFileOutput = null;
                try {
                    ditaFileOutput = new OutputStreamWriter(fileOutput, UTF8);
                    if (outputFileName.getPath().equals(changeTable.get(outputFileName.getPath()))) {
                        // if the output file is newly generated file
                        // write the xml header and workdir PI into new file
                        writeStartDocument(ditaFileOutput);
                        final File workDir = outputFileName.getParentFile().getAbsoluteFile();
                        if (!OS_NAME.toLowerCase().contains(OS_NAME_WINDOWS)) {
                            writeProcessingInstruction(ditaFileOutput, PI_WORKDIR_TARGET, workDir.getAbsolutePath());
                        } else {
                            writeProcessingInstruction(ditaFileOutput, PI_WORKDIR_TARGET, UNIX_SEPARATOR + workDir.getAbsolutePath());
                        }
                        writeProcessingInstruction(ditaFileOutput, PI_WORKDIR_TARGET_URI, workDir.toURI().toString());

                        if ((conflictTable.get(outputFileName.getPath()) != null)) {
                            final String relativePath = getRelativeUnixPath(filePath + UNIX_SEPARATOR + FILE_NAME_STUB_DITAMAP, conflictTable.get(outputFileName.getPath()));
                            String path2project = getRelativeUnixPath(relativePath);
                            if (null == path2project) {
                                path2project = "";
                            }
                            writeProcessingInstruction(ditaFileOutput, PI_PATH2PROJ_TARGET, path2project);
                        }
                    }
                    if (needWriteDitaTag) {
                        final AttributesImpl atts = new AttributesImpl();
                        addOrSetAttribute(atts, ATTRIBUTE_NAMESPACE_PREFIX_DITAARCHVERSION, ditaarchNSValue);
                        addOrSetAttribute(atts, ATTRIBUTE_PREFIX_DITAARCHVERSION + COLON + ATTRIBUTE_NAME_DITAARCHVERSION, "1.2");
                        writeStartElement(ditaFileOutput, ELEMENT_NAME_DITA, atts);
                    }
                    // write the final result to the output file
                    ditaFileOutput.write(((StringWriter) output).getBuffer().toString());
                    if (needWriteDitaTag) {
                        writeEndElement(ditaFileOutput, ELEMENT_NAME_DITA);
                    }
                    ditaFileOutput.flush();
                } finally {
                    ditaFileOutput.close();
                }
                // restore back original output
                output = tempWriter;
                topicID = tempTopicID;
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /** Check whether href needs to be updated */
    private boolean checkHREF(final Attributes atts) {
        if (atts.getValue(ATTRIBUTE_NAME_HREF) == null) {
            return false;
        }
        return !ATTR_SCOPE_VALUE_EXTERNAL.equals(atts.getValue(ATTRIBUTE_NAME_SCOPE));
    }

    /**
     * 
     * Get the first topic id from the given dita file.
     * 
     * @param absolutePathToFile The absolute path to a dita file.
     * @return The first topic id from the given dita file if success, otherwise
     *         {@code null} string is returned.
     */
    private String getFirstTopicId(final String absolutePathToFile) {
        if (absolutePathToFile == null || !isAbsolutePath(absolutePathToFile)) {
            return null;
        }
        final StringBuilder firstTopicId = new StringBuilder();
        final TopicIdParser parser = new TopicIdParser(firstTopicId);
        try {
            final XMLReader reader = getXMLReader();
            reader.setContentHandler(parser);
            reader.parse(new File(absolutePathToFile).toURI().toString());
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
        if (firstTopicId.length() == 0) {
            return null;
        }
        return firstTopicId.toString();
    }

    /**
     * Create topicmeta node.
     * 
     * @param topic document element of a topic file.
     * @return created and populated topicmeta
     */
    private Element createTopicMeta(final Element topic) {
        final Document doc = rootTopicref.getOwnerDocument();
        final Element topicmeta = doc.createElement(MAP_TOPICMETA.localName);
        topicmeta.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_TOPICMETA.toString());

        // iterate the node.
        if (topic != null) {
            final Element title = getElementNode(topic, TOPIC_TITLE);
            final Element titlealts = getElementNode(topic, TOPIC_TITLEALTS);
            final Element navtitle = titlealts != null ? getElementNode(titlealts, TOPIC_NAVTITLE) : null;
            final Element shortDesc = getElementNode(topic, TOPIC_SHORTDESC);

            final Element navtitleNode = doc.createElement(TOPIC_NAVTITLE.localName);
            navtitleNode.setAttribute(ATTRIBUTE_NAME_CLASS, TOPIC_NAVTITLE.toString());
            // append navtitle node
            if (navtitle != null) {
                final String text = getText(navtitle);
                final Text titleText = doc.createTextNode(text);
                navtitleNode.appendChild(titleText);
                topicmeta.appendChild(navtitleNode);
            } else {
                final String text = getText(title);
                final Text titleText = doc.createTextNode(text);
                navtitleNode.appendChild(titleText);
                topicmeta.appendChild(navtitleNode);
            }

            // append gentext pi
            final Node pi = doc.createProcessingInstruction("ditaot", "gentext");
            topicmeta.appendChild(pi);

            // append linktext
            final Element linkTextNode = doc.createElement(TOPIC_LINKTEXT.localName);
            linkTextNode.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_LINKTEXT.toString());
            final String text = getText(title);
            final Text textNode = doc.createTextNode(text);
            linkTextNode.appendChild(textNode);
            topicmeta.appendChild(linkTextNode);

            // append genshortdesc pi
            final Node pii = doc.createProcessingInstruction("ditaot", "genshortdesc");
            topicmeta.appendChild(pii);

            // append shortdesc
            final Element shortDescNode = doc.createElement(TOPIC_SHORTDESC.localName);
            shortDescNode.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_SHORTDESC.toString());
            final String shortDescText = getText(shortDesc);
            final Text shortDescTextNode = doc.createTextNode(shortDescText);
            shortDescNode.appendChild(shortDescTextNode);
            topicmeta.appendChild(shortDescNode);
        }
        return topicmeta;
    }

    // DOM utility methods

    private static final List<String> excludeList;
    static {
        final List<String> el = new ArrayList<String>();
        el.add(TOPIC_INDEXTERM.toString());
        el.add(TOPIC_DRAFT_COMMENT.toString());
        el.add(TOPIC_REQUIRED_CLEANUP.toString());
        el.add(TOPIC_DATA.toString());
        el.add(TOPIC_DATA_ABOUT.toString());
        el.add(TOPIC_UNKNOWN.toString());
        el.add(TOPIC_FOREIGN.toString());
        excludeList = Collections.unmodifiableList(el);
    }

    /**
     * Search for the special kind of node by specialized value.
     * @param root place may have the node.
     * @param searchKey keyword for search.
     * @param attrName attribute name for search.
     * @param classValue class value for search.
     * @return element.
     */
    private Element searchForNode(final Element root, final String searchKey, final String attrName,
            final DitaClass classValue) {
        if (root == null) {
            return null;
        }
        final Queue<Element> queue = new LinkedList<Element>();
        queue.offer(root);
        while (!queue.isEmpty()) {
            final Element pe = queue.poll();
            final NodeList pchildrenList = pe.getChildNodes();
            for (int i = 0; i < pchildrenList.getLength(); i++) {
                final Node node = pchildrenList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    queue.offer((Element) node);
                }
            }
            if (pe.getAttribute(ATTRIBUTE_NAME_CLASS) == null || !classValue.matches(pe)) {
                continue;
            }
            final Attr value = pe.getAttributeNode(attrName);
            if (value == null) {
                continue;
            }
            if (searchKey.equals(value.getValue())){
                return pe;
            }
        }
        return null;
    }
    
    /**
     * Get text value of a node.
     *
     * @param root root node
     * @return text value
     */
    private String getText(final Node root) {
        if (root == null) {
            return "";
        } else {
            final StringBuilder result = new StringBuilder(1024);
            if (root.hasChildNodes()) {
                final NodeList list = root.getChildNodes();
                for (int i = 0; i < list.getLength(); i++) {
                    final Node childNode = list.item(i);
                    if (childNode.getNodeType() == Node.ELEMENT_NODE) {
                        final Element e = (Element)childNode;
                        final String value = e.getAttribute(ATTRIBUTE_NAME_CLASS);
                        if (!excludeList.contains(value)) {
                            final String s = getText(e);
                            result.append(s);
                        }
                    } else if(childNode.getNodeType() == Node.TEXT_NODE) {
                        result.append(childNode.getNodeValue());
                    }
                }
            } else if(root.getNodeType() == Node.TEXT_NODE) {
                result.append(root.getNodeValue());
            }
            return result.toString();
        }
    }

    /**
     * get the document node of a topic file.
     * @param absolutePathToFile topic file
     * @return element.
     */
    private Element getTopicDoc(final String absolutePathToFile){
        final DocumentBuilder builder = getDocumentBuilder();
        try {
            final Document doc = builder.parse(absolutePathToFile);
            return doc.getDocumentElement();
        } catch (final SAXException e) {
            e.printStackTrace();
        } catch (final IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * get topicmeta's child(e.g navtitle, shortdesc) tag's value(text-only).
     * @param element input element
     * @return text value
     */
    private String getChildElementValueOfTopicmeta(final Element element, final DitaClass classValue) {
        if (element.hasChildNodes()) {
            final Element topicMeta = getElementNode(element, MAP_TOPICMETA);
            if (topicMeta != null) {
                final Element elem = getElementNode(topicMeta, classValue);
                if (elem != null) {
                    return getText(elem);
                }
            }
        }
        return null;
    }

    /**
     * Get specific element node from child nodes.
     *
     * @param element parent node
     * @param classValue DITA class to search for
     * @return element node, {@code null} if not found
     */
    private Element getElementNode(final Element element, final DitaClass classValue) {
        final NodeList list = element.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            final Node node = list.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                final Element child = (Element) node;
                if (classValue.matches(child)) {
                    return child;
                }
            }
        }
        return null;
    }

    // SAX serialization methods

    /**
     * Convenience method to write document start.
     */
    private void writeStartDocument(final Writer output) throws IOException {
        output.write(XML_HEAD);
    }

    /**
     * Convenience method to write an end element.
     *
     * @param name element name
     */
    private void writeStartElement(final Writer output, final String name, final Attributes atts) throws IOException {
        output.write(LESS_THAN);
        output.write(name);
        for (int i = 0; i < atts.getLength(); i++) {
            output.write(STRING_BLANK);
            output.write(atts.getQName(i));
            output.write(EQUAL);
            output.write(QUOTATION);
            output.write(escapeXML(atts.getValue(i)));
            output.write(QUOTATION);
        }
        output.write(GREATER_THAN);
    }

    /**
     * Convenience method to write an end element.
     *
     * @param name element name
     */
    private void writeEndElement(final Writer output, final String name) throws IOException {
        output.write(LESS_THAN);
        output.write(SLASH);
        output.write(name);
        output.write(GREATER_THAN);
    }

    /**
     * Convenience method to write a processing instruction.
     *
     * @param name PI name
     * @param value PI value, may be {@code null}
     */
    private void writeProcessingInstruction(final Writer output, final String name, final String value)
            throws IOException {
        output.write(LESS_THAN);
        output.write(QUESTION);
        output.write(name);
        if (value != null) {
            output.write(STRING_BLANK);
            output.write(value);
        }
        output.write(QUESTION);
        output.write(GREATER_THAN);
    }

}
