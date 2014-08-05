/*
 * Copyright (C) 2014 Michell Bak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.miz.filesources;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import android.content.Context;
import android.database.Cursor;

import com.miz.abstractclasses.TvShowFileSource;
import com.miz.db.DbAdapterTvShow;
import com.miz.db.DbAdapterTvShowEpisode;
import com.miz.functions.ColumnIndexCache;
import com.miz.functions.DbEpisode;
import com.miz.functions.FileSource;
import com.miz.functions.MizLib;
import com.miz.mizuu.MizuuApplication;

public class FileTvShow extends TvShowFileSource<File> {

	private HashMap<String, String> existingEpisodes = new HashMap<String, String>();
	private File tempFile;

	public FileTvShow(Context context, FileSource fileSource, boolean ignoreRemovedFiles, boolean subFolderSearch, boolean clearLibrary, boolean disableEthernetWiFiCheck) {
		super(context, fileSource, ignoreRemovedFiles, subFolderSearch, clearLibrary, disableEthernetWiFiCheck);
	}

	@Override
	public void removeUnidentifiedFiles() {
		DbAdapterTvShowEpisode db = MizuuApplication.getTvEpisodeDbAdapter();
		List<DbEpisode> dbEpisodes = getDbEpisodes();

		File temp;
		int count = dbEpisodes.size();
		for (int i = 0; i < count; i++) {
			if (!dbEpisodes.get(i).isNetworkFile() && !dbEpisodes.get(i).isUpnpFile() && dbEpisodes.get(i).isUnidentified()) {
				temp = new File(dbEpisodes.get(i).getFilepath());
				if (temp.exists())
					db.deleteEpisode(dbEpisodes.get(i).getRowId());
			}
		}
	}

	@Override
	public void removeUnavailableFiles() {
		ArrayList<DbEpisode> dbEpisodes = new ArrayList<DbEpisode>(), removedEpisodes = new ArrayList<DbEpisode>();

		// Fetch all the episodes from the database
		DbAdapterTvShowEpisode db = MizuuApplication.getTvEpisodeDbAdapter();

		ColumnIndexCache cache = new ColumnIndexCache();
		Cursor tempCursor = db.getAllEpisodesInDatabase(ignoreRemovedFiles());
		while (tempCursor.moveToNext()) {
			try {
				dbEpisodes.add(new DbEpisode(getContext(),
						tempCursor.getString(cache.getColumnIndex(tempCursor, DbAdapterTvShowEpisode.KEY_FILEPATH)),
						tempCursor.getString(cache.getColumnIndex(tempCursor, DbAdapterTvShowEpisode.KEY_ROWID)),
						tempCursor.getString(cache.getColumnIndex(tempCursor, DbAdapterTvShowEpisode.KEY_SHOW_ID)),
						tempCursor.getString(cache.getColumnIndex(tempCursor, DbAdapterTvShowEpisode.KEY_SEASON)),
						tempCursor.getString(cache.getColumnIndex(tempCursor, DbAdapterTvShowEpisode.KEY_EPISODE))
						)
						);
			} catch (NullPointerException e) {
			} finally {
				tempCursor.close();
				cache.clear();
			}
		}

		int count = dbEpisodes.size();
		for (int i = 0; i < dbEpisodes.size(); i++) {
			if (!new File(dbEpisodes.get(i).getFilepath()).exists()) {
				boolean deleted = db.deleteEpisode(dbEpisodes.get(i).getRowId());
				if (deleted)
					removedEpisodes.add(dbEpisodes.get(i));
			}
		}

		count = removedEpisodes.size();
		for (int i = 0; i < count; i++) {
			if (db.getEpisodeCount(removedEpisodes.get(i).getShowId()) == 0) { // No more episodes for this show
				DbAdapterTvShow dbShow = MizuuApplication.getTvDbAdapter();
				boolean deleted = dbShow.deleteShow(removedEpisodes.get(i).getShowId());

				if (deleted) {
					MizLib.deleteFile(new File(removedEpisodes.get(i).getThumbnail()));
					MizLib.deleteFile(new File(removedEpisodes.get(i).getBackdrop()));
				}
			}

			MizLib.deleteFile(new File(removedEpisodes.get(i).getEpisodeCoverPath()));
		}

		// Clean up
		dbEpisodes.clear();
		removedEpisodes.clear();
	}

	@Override
	public List<String> searchFolder() {
		DbAdapterTvShowEpisode dbHelper = MizuuApplication.getTvEpisodeDbAdapter();
		Cursor cursor = dbHelper.getAllEpisodesInDatabase(ignoreRemovedFiles()); // Query database to return all show episodes to a cursor
		ColumnIndexCache cache = new ColumnIndexCache();
		
		try {
			while (cursor.moveToNext()) // Add all show episodes in cursor to ArrayList of all existing episodes
				existingEpisodes.put(cursor.getString(cache.getColumnIndex(cursor, DbAdapterTvShowEpisode.KEY_FILEPATH)), "");
		} catch (Exception e) {
		} finally {
			cursor.close(); // Close cursor
			cache.clear();
		}

		TreeSet<String> results = new TreeSet<String>();

		// Do a recursive search in the file source folder
		recursiveSearch(getFolder(), results);

		List<String> list = new ArrayList<String>();

		Iterator<String> it = results.iterator();
		while (it.hasNext())
			list.add(it.next());

		return list;
	}

	@Override
	public void recursiveSearch(File folder, TreeSet<String> results) {
		try {
			if (searchSubFolders()) {
				if (folder.isDirectory()) {
					String[] childs = folder.list();				
					for (int i = 0; i < childs.length; i++) {
						tempFile = new File(folder.getAbsolutePath() + "/" + childs[i]);
						recursiveSearch(tempFile, results);
					}
				} else {
					addToResults(folder, results);
				}
			} else {
				File[] children = folder.listFiles();
				for (int i = 0; i < children.length; i++)
					addToResults(children[i], results);
			}
		} catch (Exception e) {}
	}

	@Override
	public void addToResults(File file, TreeSet<String> results) {
		if (MizLib.checkFileTypes(file.getAbsolutePath())) {
			if (file.length() < getFileSizeLimit())
				return;

			if (!clearLibrary())
				if (existingEpisodes.get(file.getAbsolutePath()) != null) return;

			//Add the file if it reaches this point
			results.add(file.getAbsolutePath());
		}
	}

	@Override
	public File getRootFolder() {
		return new File(getFileSource().getFilepath());
	}

	@Override
	public String toString() {
		return getRootFolder().getAbsolutePath();
	}
}