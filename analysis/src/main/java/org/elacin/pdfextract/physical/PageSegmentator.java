/*
 * Copyright 2010 Øyvind Berg (elacin@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elacin.pdfextract.physical;

import org.apache.log4j.Logger;
import org.elacin.pdfextract.Constants;
import org.elacin.pdfextract.content.*;
import org.elacin.pdfextract.geom.MathUtils;
import org.elacin.pdfextract.geom.Rectangle;
import org.elacin.pdfextract.geom.RectangleCollection;
import org.elacin.pdfextract.geom.Sorting;
import org.elacin.pdfextract.physical.column.ColumnFinder;
import org.elacin.pdfextract.physical.graphics.CategorizedGraphics;
import org.elacin.pdfextract.physical.graphics.GraphicSegmentator;
import org.elacin.pdfextract.physical.graphics.GraphicSegmentatorImpl;
import org.elacin.pdfextract.physical.line.LineSegmentator;
import org.elacin.pdfextract.physical.paragraph.ParagraphSegmentator;
import org.elacin.pdfextract.style.Style;
import org.elacin.pdfextract.tree.GraphicsNode;
import org.elacin.pdfextract.tree.LineNode;
import org.elacin.pdfextract.tree.PageNode;
import org.elacin.pdfextract.tree.ParagraphNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static org.elacin.pdfextract.geom.Sorting.createSmallestFirstQueue;
import static org.elacin.pdfextract.logical.Formulas.textSeemsToBeFormula;

/**
 * Created by IntelliJ IDEA. User: elacin Date: 09.12.10 Time: 23.24 To change this template use
 * File | Settings | File Templates.
 */
public class PageSegmentator {
// ------------------------------ FIELDS ------------------------------

@NotNull
private static final Logger               log                  = Logger.getLogger(PageSegmentator.class);
private static final ParagraphSegmentator paragraphSegmentator = new ParagraphSegmentator();


// -------------------------- PUBLIC STATIC METHODS --------------------------

public static PageNode analyzePage(@NotNull PhysicalPage page) {
    final PhysicalPageRegion mainRegion = page.getMainRegion();

    final ParagraphNumberer numberer = new ParagraphNumberer(page.getPageNumber());

    GraphicSegmentator graphicSegmentator = new GraphicSegmentatorImpl(mainRegion.getPos());

    final CategorizedGraphics categorizedGraphics
            = graphicSegmentator.categorizeGraphics(page.getAllGraphics(), mainRegion);

    mainRegion.addContents(categorizedGraphics.getContents());

    /* first separate out what is contained by graphics */
    extractGraphicalRegions(categorizedGraphics, mainRegion);

    PageRegionSplitBySpacing.splitOfTopText(mainRegion, 0.1f);

    PageRegionSplitBySeparators.splitRegionBySeparators(mainRegion, categorizedGraphics);


    /* This will detect column boundaries and split up all regions */
    recursivelyDivide(mainRegion, categorizedGraphics);

    /* this is to make text ordering work, if it was in the main region it would destroy
        the sorting */
    mainRegion.ensureAllContentInLeafNodes();


    /* first create the page node which will hold everything */
    final PageNode ret = new PageNode(page.getPageNumber());

    StringBuffer sb = new StringBuffer();
    printRegions(sb, mainRegion, 0);
    System.out.println(sb);

    createParagraphsForRegion(ret, mainRegion, numberer, false);

    if (log.isInfoEnabled()) {
        log.info("LOG00940:Page had " + ret.getChildren().size() + " paragraphs");
    }

    return ret;
}

private static void printRegions(final StringBuffer sb,
                                 final PhysicalPageRegion region,
                                 final int indent)
{

    for (int i = 0; i < indent; i++) {
        sb.append(" ");
    }
    sb.append("region ").append(region.getPos());
    if (region.isGraphicalRegion()) {
        sb.append(" graphical ");
    }
    sb.append("\n");

    for (PhysicalPageRegion sub : region.getSubregions()) {
        printRegions(sb, sub, indent + 4);
    }
}

// -------------------------- STATIC METHODS --------------------------

private static void createParagraphsForRegion(final PageNode page,
                                              final PhysicalPageRegion region,
                                              final ParagraphNumberer numberer,
                                              boolean wasContainedInGraphic)
{
    numberer.newRegion();
    paragraphSegmentator.setMedianVerticalSpacing(region.getMedianOfVerticalDistances());

    final ContentGrouper contentGrouper = new ContentGrouper(region);
    final List<RectangleCollection> blocks = contentGrouper.findBlocksOfContent();

    Collections.sort(blocks, Sorting.regionComparator);

    //    combineReallySmallBlocks(blocks);


    for (RectangleCollection block : blocks) {
        /** start by separating all the graphical content in a block.
         *  This is surely an oversimplification, but it think it should work for our
         *  purposes. This very late combination of graphics will be used to grab all text
         *  which is contained within
         * */
        @Nullable Rectangle graphicBounds = extractBoundOfPlainGraphics(block,
                                                                        region.getContainingGraphic());


        final List<LineNode> lines = LineSegmentator.createLinesFromBlocks(block,
                                                                           numberer.getPage());

        /** separate out everything related to graphics in this part of the page into a single
         *   paragraph */
        if (graphicBounds != null) {
            GraphicsNode graphical = null;

            if (wasContainedInGraphic) {
                for (GraphicsNode graphicsNode : page.getGraphics()) {
                    if (graphicBounds.getPos().containedBy(graphicsNode)) {
                        graphical = graphicsNode;
                        break;
                    }
                }
                if (graphical == null) {
                    graphical = page.getGraphics().get(page.getGraphics().size() - 1);
                    graphical.setGraphicsPos(graphical.getGraphicsPos().union(graphicBounds));
                }
            } else {
                graphical = new GraphicsNode(graphicBounds);
                page.addGraphics(graphical);
            }

            ParagraphNode paragraph = new ParagraphNode(numberer.getParagraphId(false));
            for (Iterator<LineNode> iterator = lines.iterator(); iterator.hasNext();) {
                final LineNode line = iterator.next();
                if (region.isGraphicalRegion() || graphicBounds.intersectsWith(line.getPos())) {
                    paragraph.addChild(line);
                    iterator.remove();
                }
            }

            if (!paragraph.getChildren().isEmpty()) {
                graphical.addChild(paragraph);
            }
        }


        /* then add the rest of the paragraphs */
        page.addChildren(paragraphSegmentator.segmentParagraphsByStyleAndDistance(lines, numberer));

    }

    final List<PhysicalPageRegion> subregions = region.getSubregions();
    Collections.sort(subregions, Sorting.regionComparator);

    for (PhysicalPageRegion subregion : subregions) {
        createParagraphsForRegion(page, subregion, numberer, region.isGraphicalRegion());
    }
}

private static void combineReallySmallBlocks(final List<RectangleCollection> blocks) {
    for (int i = 0; i < blocks.size() - 1; i++) {
        final RectangleCollection current = blocks.get(i);
        final RectangleCollection after = blocks.get(i + 1);

        if (after.getContents().size() > 3) {
            continue;
        }

        float afterMiddleY = after.getPos().getMiddleY();
        if (afterMiddleY > current.getPos().getEndY()) {
            continue;
        }

        if (afterMiddleY < current.getPos().getY()) {
            continue;
        }

        /* since 'current' might be many lines, pick out the one roughly corresponding to
            'after' and see if it probably is a formula */

        List<PhysicalText> sameLineAsAfter = new ArrayList<PhysicalText>();
        for (PhysicalContent content : current.getContents()) {
            if (content.isText() && content.getPos().getEndY() > afterMiddleY
                        && content.getPos().getY() < afterMiddleY) {
                sameLineAsAfter.add(content.getPhysicalText());
            }
        }

        if (textSeemsToBeFormula(sameLineAsAfter)) {
            current.addContents(after.getContents());
            blocks.remove(after);
            if (log.isInfoEnabled()) {
                String afterString = after.getContents().get(0).getPhysicalText().getText();
                log.info("LOG01350:Combining " + afterString + " with rest of contents on line");
            };
            i--;

        }
    }
}

/**
 * separate out the content which is contained within a graphic. sort the graphics by smallest,
 * because they might overlap.
 *
 * @param graphics
 * @param r
 */
private static void extractGraphicalRegions(@NotNull CategorizedGraphics graphics,
                                            @NotNull PhysicalPageRegion r)
{
    PriorityQueue<GraphicContent> queue = createSmallestFirstQueue(graphics.getContainers());

    while (!queue.isEmpty()) {
        final GraphicContent graphic = queue.remove();
        try {
            r.extractSubRegionFromGraphic(graphic);
        } catch (Exception e) {
            log.info("LOG00320:Could not divide page::" + e.getMessage());
            if (graphic.getPos().area() < r.getPos().area() * 0.4f) {
                if (log.isInfoEnabled()) {
                    log.info("LOG00690:Adding " + graphic + " as content");
                }
                graphic.setCanBeAssigned(true);
                graphic.setStyle(Style.GRAPHIC_IMAGE);
                r.addContent(graphic);
            } else {
                graphics.getGraphicsToRender().remove(graphic);
            }
        }
    }
}

@Nullable
private static Rectangle extractBoundOfPlainGraphics(final RectangleCollection block,
                                                     final GraphicContent containingGraphic)
{
    List<PhysicalContent> nontextualContent = new ArrayList<PhysicalContent>();

    for (Iterator<PhysicalContent> iterator = block.getContents().iterator(); iterator.hasNext();) {
        final PhysicalContent content = iterator.next();

        if (!content.isGraphic()) {
            continue;
        }

        final GraphicContent g = content.getGraphicContent();
        if (g.isMathBar() || g.isSeparator()) {
            continue;
        }

        nontextualContent.add(content);
        iterator.remove();
    }

    if (containingGraphic != null) {
        nontextualContent.add(containingGraphic);
    }

    @Nullable Rectangle nonTextualBound = null;
    if (!nontextualContent.isEmpty()) {
        nonTextualBound = MathUtils.findBounds(nontextualContent);
        assert !nonTextualBound.equals(Rectangle.EMPTY_RECTANGLE);
        //wtf?
        if (nonTextualBound.equals(Rectangle.EMPTY_RECTANGLE)) {
            nonTextualBound = null;
        }
    }
    return nonTextualBound;
}

private static void recursivelyDivide(@NotNull PhysicalPageRegion region,
                                      CategorizedGraphics graphics)
{


    final List<WhitespaceRectangle> whitespaces = ColumnFinder.findWhitespace(region);
    region.addWhitespace(whitespaces);

    if (!Constants.COLUMNS_ENABLE_COLUMN_DETECTION) {
        return;
    }

    final List<WhitespaceRectangle> columnBoundaries = ColumnFinder.extractColumnBoundaries(region,
                                                                                            whitespaces);

    for (WhitespaceRectangle columnBoundary : columnBoundaries) {
        log.info("LOG01050:Region " + region + ", column boundary: " + columnBoundary);
    }

    region.addWhitespace(columnBoundaries);

    for (PhysicalPageRegion subRegion : region.getSubregions()) {
        recursivelyDivide(subRegion, graphics);
    }

    Collections.sort(columnBoundaries, Sorting.sortByHigherX);
    for (WhitespaceRectangle boundary : columnBoundaries) {
        final Rectangle bpos = boundary.getPos();

        Rectangle right = new Rectangle(bpos.getMiddleX(), bpos.getY() + 1,
                                        region.getPos().getEndX() - bpos.getMiddleX(),
                                        bpos.getHeight() - 1);

        region.extractSubRegionFromBound(right);
    }
}
}
