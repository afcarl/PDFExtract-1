/*
 * Copyright 2010 Øyvind Berg (elacin@gmail.com)
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.elacin.pdfextract.util;

import org.elacin.pdfextract.physical.content.HasPosition;
import org.elacin.pdfextract.physical.content.PhysicalContent;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Created by IntelliJ IDEA. User: elacin Date: Nov 2, 2010 Time: 1:20:36 AM To change this template
 * use File | Settings | File Templates.
 */
public class RectangleCollection extends PhysicalContent {
// ------------------------------ FIELDS ------------------------------

@NotNull
private final List<PhysicalContent> contents;

/* calculating all the intersections while searching is expensive, so keep this cached.
    will be pruned on update */
@NotNull
private Map<Integer, List<PhysicalContent>> yCache = new HashMap<Integer, List<PhysicalContent>>();
@NotNull
private Map<Integer, List<PhysicalContent>> xCache = new HashMap<Integer, List<PhysicalContent>>();

/**
 * if all the contents in this collection is contained within some content (say a figure with text
 * embedded, that will be marked here
 */
private final PhysicalContent containedIn;

// --------------------------- CONSTRUCTORS ---------------------------

public RectangleCollection(@NotNull final Collection<? extends PhysicalContent> newContents,
                           final PhysicalContent containedIn)
{
	super(newContents);
	this.containedIn = containedIn;

	contents = new ArrayList<PhysicalContent>(newContents.size());
	contents.addAll(newContents);
}

// -------------------------- STATIC METHODS --------------------------

private static void sortListByXCoordinate(final List<PhysicalContent> list) {
	final Comparator<PhysicalContent> sortByX = new Comparator<PhysicalContent>() {
		@Override
		public int compare(@NotNull final PhysicalContent o1, @NotNull final PhysicalContent o2) {
			return Float.compare(o1.getPosition().getX(), o2.getPosition().getX());
		}
	};
	Collections.sort(list, sortByX);
}

private static void sortListByYCoordinate(final List<PhysicalContent> list) {
	final Comparator<PhysicalContent> sortByY = new Comparator<PhysicalContent>() {
		@Override
		public int compare(@NotNull final PhysicalContent o1, @NotNull final PhysicalContent o2) {
			return Float.compare(o1.getPosition().getY(), o2.getPosition().getY());
		}
	};
	Collections.sort(list, sortByY);
}

// --------------------- GETTER / SETTER METHODS ---------------------

public PhysicalContent getContainedIn() {
	return containedIn;
}

@NotNull
public List<PhysicalContent> getContents() {
	return contents;
}

// -------------------------- PUBLIC METHODS --------------------------

public void addContent(Collection<? extends PhysicalContent> newContents) {
	contents.addAll(newContents);
	clearCache();
	setPositionFromContentList(contents);
}

public void addContent(final PhysicalContent content) {
	contents.add(content);
	clearCache();
	setPositionFromContentList(contents);
}

@SuppressWarnings({"NumericCastThatLosesPrecision"})
public List<PhysicalContent> findContentAtXIndex(float x) {
	return findContentAtXIndex((int) x);
}

public List<PhysicalContent> findContentAtXIndex(int x) {
	if (!xCache.containsKey(x)) {
		final Rectangle searchRectangle
				= new Rectangle((float) x, getPosition().getY(), 1.0f, getPosition().getHeight());
		final List<PhysicalContent> result = findRectanglesIntersectingWith(searchRectangle);
		sortListByYCoordinate(result);
		xCache.put(x, result);
	}
	return xCache.get(x);
}

@SuppressWarnings({"NumericCastThatLosesPrecision"})
public List<PhysicalContent> findContentAtYIndex(float y) {
	return findContentAtYIndex((int) y);
}

public List<PhysicalContent> findContentAtYIndex(int y) {
	if (!yCache.containsKey(y)) {
		final Rectangle searchRectangle
				= new Rectangle(getPosition().getX(), (float) y, getPosition().getWidth(), 1.0F);
		final List<PhysicalContent> result = findRectanglesIntersectingWith(searchRectangle);
		sortListByXCoordinate(result);
		yCache.put(y, result);
	}
	return yCache.get(y);
}

@NotNull
public List<PhysicalContent> findRectanglesIntersectingWith(@NotNull final HasPosition search) {
	final List<PhysicalContent> ret = new ArrayList<PhysicalContent>(50);
	for (PhysicalContent r : contents) {
		if (search.getPosition().intersectsWith(r.getPosition())) {
			ret.add(r);
		}
	}
	return ret;
}

@NotNull
public List<PhysicalContent> findSurrounding(@NotNull final PhysicalContent text,
                                             final int distance)
{
	final Rectangle bound = text.getPosition();

	Rectangle searchRectangle = new Rectangle(
			bound.getX() - (float) distance,
			bound.getY() - (float) distance,
			bound.getWidth() + (float) distance, bound.getHeight() + (float) distance);

	final List<PhysicalContent> ret = findRectanglesIntersectingWith(searchRectangle);

	if (ret.contains(text)) {
		ret.remove(text);
	}

	return ret;
}

public float getHeight() {
	return getPosition().getHeight();
}

public float getWidth() {
	return getPosition().getWidth();
}

public void removeContent(@NotNull Collection<PhysicalContent> listToRemove) {
	for (PhysicalContent toRemove : listToRemove) {
		if (!contents.remove(toRemove)) {
			throw new RuntimeException("Region " + this + ": Could not remove " + toRemove);
		}
	}
	clearCache();
	setPositionFromContentList(contents);
}

@NotNull
public List<PhysicalContent> searchInDirectionFromOrigin(@NotNull Direction dir,
                                                         @NotNull PhysicalContent origin,
                                                         float distance)
{
	final Rectangle pos = origin.getPosition();
	final float x = pos.getX() + dir.xDiff * distance;
	final float y = pos.getY() + dir.yDiff * distance;
	final Rectangle search = new Rectangle(x, y, pos.getWidth(), pos.getHeight());

	final List<PhysicalContent> ret = findRectanglesIntersectingWith(search);
	if (ret.contains(origin)) {
		ret.remove(origin);
	}
	return ret;
}

// -------------------------- OTHER METHODS --------------------------

protected void clearCache() {
	yCache.clear();
	xCache.clear();
}

// -------------------------- ENUMERATIONS --------------------------

public enum Direction {
	N(0, 1),
	NE(1, 1),
	E(1, 0),
	SE(1, -1),
	S(0, -1),
	SW(-1, -1),
	W(-1, 0),
	NW(-1, 1);
	float xDiff;
	float yDiff;

	Direction(final float xDiff, final float yDiff) {
		this.xDiff = xDiff;
		this.yDiff = yDiff;
	}
}
}
