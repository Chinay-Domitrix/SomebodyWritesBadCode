/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package dev.badbird;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.GitCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.MultipleParentsNotAllowedException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.events.WorkingTreeModifiedEvent;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.merge.MergeMessageFormatter;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.merge.ResolveMerger.MergeFailureReason;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static java.text.MessageFormat.format;
import static org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING;
import static org.eclipse.jgit.api.MergeResult.MergeStatus.FAILED;
import static org.eclipse.jgit.internal.JGitText.get;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_ABBREV_STRING_LENGTH;
import static org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE;
import static org.eclipse.jgit.lib.ObjectIdRef.Unpeeled;
import static org.eclipse.jgit.lib.Ref.Storage.LOOSE;
import static org.eclipse.jgit.lib.Repository.shortenRefName;
import static org.eclipse.jgit.merge.MergeStrategy.RECURSIVE;

/**
 * A class used to execute a {@code revert} command. It has setters for all
 * supported options and arguments of this command and a {@link #call()} method
 * to finally execute the command. Each instance of this class should only be
 * used for one invocation of the command (means: one call to {@link #call()})
 *
 * @see <a
 * href="http://www.kernel.org/pub/software/scm/git/docs/git-revert.html"
 * >Git documentation about revert</a>
 */
public class NewRevertCommand extends GitCommand<RevCommit> {
	private final LinkedList<Ref> commits = new LinkedList<>();
	private String ourCommitName = null;
	private final LinkedList<Ref> revertedRefs = new LinkedList<>();
	private MergeResult failingResult;
	private List<String> unmergedPaths;
	private MergeStrategy strategy = RECURSIVE;
	private ProgressMonitor monitor = INSTANCE;
	private String customShortName = null, customMessage = null;

	/**
	 * <p>
	 * Constructor for RevertCommand.
	 * </p>
	 *
	 * @param repo the {@link org.eclipse.jgit.lib.Repository}
	 */
	public NewRevertCommand(Repository repo) {
		super(repo);
	}

	public void setCustomMessage(String customMessage) {
		this.customMessage = customMessage;
	}

	public void setCustomShortName(String customShortName) {
		this.customShortName = customShortName;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Executes the {@code revert} command with all the options and parameters
	 * collected by the setter methods (e.g. {@link #include(Ref)} of this
	 * class. Each instance of this class should only be used for one invocation
	 * of the command. Don't call this method twice on an instance.
	 */
	@Override
	public RevCommit call() throws GitAPIException {
		RevCommit newHead = null;
		checkCallable();
		try (RevWalk revWalk = new RevWalk(repo)) {
			// get the head commit
			Ref headRef = repo.exactRef(HEAD);
			if (headRef == null) throw new NoHeadException(get().commitOnRepoWithoutHEADCurrentlyNotSupported);
			RevCommit headCommit = revWalk.parseCommit(headRef.getObjectId());
			newHead = headCommit;
			// loop through all refs to be reverted
			for (Ref src : commits) {
				// get the commit to be reverted
				// handle annotated tags
				ObjectId srcObjectId = src.getPeeledObjectId();
				if (srcObjectId == null) srcObjectId = src.getObjectId();
				RevCommit srcCommit = revWalk.parseCommit(srcObjectId);
				// get the parent of the commit to revert
				if (srcCommit.getParentCount() != 1)
					throw new MultipleParentsNotAllowedException(format(get().canOnlyRevertCommitsWithOneParent, srcCommit.name(), srcCommit.getParentCount()));
				RevCommit srcParent = srcCommit.getParent(0);
				revWalk.parseHeaders(srcParent);
				String ourName = calculateOurName(headRef);
				String revertName = "%s %s".formatted(srcCommit.getId().abbreviate(OBJECT_ID_ABBREV_STRING_LENGTH).name(), srcCommit.getShortMessage());
				ResolveMerger merger = (ResolveMerger) strategy.newMerger(repo);
				merger.setWorkingTreeIterator(new FileTreeIterator(repo));
				merger.setBase(srcCommit.getTree());
				merger.setCommitNames(new String[]{"BASE", ourName, revertName});
				String shortMessage = customShortName != null ? customShortName : "Revert \"%s\"".formatted(srcCommit.getShortMessage());
				String newMessage = "%s\n\n%s".formatted(shortMessage, customMessage != null ? customMessage : "This reverts commit %s.\n".formatted(srcCommit.getId().getName()));
				if (merger.merge(headCommit, srcParent)) {
					if (!merger.getModifiedFiles().isEmpty())
						repo.fireEvent(new WorkingTreeModifiedEvent(merger.getModifiedFiles(), null));
					if (AnyObjectId.isEqual(headCommit.getTree().getId(), merger.getResultTreeId())) continue;
					DirCacheCheckout dco = new DirCacheCheckout(repo, headCommit.getTree(), repo.lockDirCache(), merger.getResultTreeId());
					dco.setFailOnConflict(true);
					dco.setProgressMonitor(monitor);
					dco.checkout();
					try (Git git = new Git(getRepository())) {
						newHead = git.commit().setMessage(newMessage).setReflogComment("revert: %s".formatted(shortMessage)).call();
					}
					revertedRefs.add(src);
					headCommit = newHead;
				} else {
					unmergedPaths = merger.getUnmergedPaths();
					Map<String, MergeFailureReason> failingPaths = merger.getFailingPaths();
					failingResult = new MergeResult(null, merger.getBaseCommitId(), new ObjectId[]{headCommit.getId(), srcParent.getId()}, (failingPaths != null) ? FAILED : CONFLICTING, strategy, merger.getMergeResults(), failingPaths, null);
					if (!merger.failed() && !unmergedPaths.isEmpty()) {
						String message = new MergeMessageFormatter().formatWithConflicts(newMessage, merger.getUnmergedPaths(), '#');
						repo.writeRevertHead(srcCommit.getId());
						repo.writeMergeCommitMsg(message);
					}
					return null;
				}
			}
		} catch (IOException e) {
			throw new JGitInternalException(format(get().exceptionCaughtDuringExecutionOfRevertCommand, e), e);
		}
		return newHead;
	}

	/**
	 * Include a {@code Ref} to a commit to be reverted
	 *
	 * @param commit a reference to a commit to be reverted into the current head
	 * @return {@code this}
	 */
	public NewRevertCommand include(Ref commit) {
		checkCallable();
		commits.add(commit);
		return this;
	}

	/**
	 * Include a commit to be reverted
	 *
	 * @param commit the Id of a commit to be reverted into the current head
	 * @return {@code this}
	 */
	public NewRevertCommand include(AnyObjectId commit) {
		return include(commit.getName(), commit);
	}

	/**
	 * Include a commit to be reverted
	 *
	 * @param name   name of a {@code Ref} referring to the commit
	 * @param commit the Id of a commit which is reverted into the current head
	 * @return {@code this}
	 */
	public NewRevertCommand include(String name, AnyObjectId commit) {
		return include(new Unpeeled(LOOSE, name, commit.copy()));
	}

	/**
	 * Set the name to be used in the "OURS" place for conflict markers
	 *
	 * @param ourCommitName the name that should be used in the "OURS" place for conflict
	 *                      markers
	 * @return {@code this}
	 */
	public NewRevertCommand setOurCommitName(String ourCommitName) {
		this.ourCommitName = ourCommitName;
		return this;
	}

	private String calculateOurName(Ref headRef) {
		if (ourCommitName != null) return ourCommitName;
		return shortenRefName(headRef.getTarget().getName());
	}

	/**
	 * Get the list of successfully reverted {@link org.eclipse.jgit.lib.Ref}'s.
	 *
	 * @return the list of successfully reverted
	 * {@link org.eclipse.jgit.lib.Ref}'s. Never <code>null</code> but
	 * maybe an empty list if no commit was successfully cherry-picked
	 */
	public List<Ref> getRevertedRefs() {
		return revertedRefs;
	}

	/**
	 * Get the result of a merge failure
	 *
	 * @return the result of a merge failure, <code>null</code> if no merge
	 * failure occurred during the revert
	 */
	public MergeResult getFailingResult() {
		return failingResult;
	}

	/**
	 * Get unmerged paths
	 *
	 * @return the unmerged paths, will be null if no merge conflicts
	 */
	public List<String> getUnmergedPaths() {
		return unmergedPaths;
	}

	/**
	 * Set the merge strategy to use for this revert command
	 *
	 * @param strategy The merge strategy to use for this revert command.
	 * @return {@code this}
	 * @since 3.4
	 */
	public NewRevertCommand setStrategy(MergeStrategy strategy) {
		this.strategy = strategy;
		return this;
	}

	/**
	 * The progress monitor associated with the revert operation. By default,
	 * this is set to <code>NullProgressMonitor</code>
	 *
	 * @param monitor a {@link org.eclipse.jgit.lib.ProgressMonitor}
	 * @return {@code this}
	 * @see NullProgressMonitor
	 * @since 4.11
	 */
	public NewRevertCommand setProgressMonitor(ProgressMonitor monitor) {
		if (monitor == null) monitor = INSTANCE;
		this.monitor = monitor;
		return this;
	}
}
