package dev.badbird;

import com.google.gson.JsonObject;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.util.Iterator;

import static com.google.gson.JsonParser.parseString;
import static java.lang.System.*;
import static java.lang.Thread.sleep;
import static java.nio.file.Files.readAllBytes;
import static org.eclipse.jgit.api.Git.cloneRepository;
import static org.eclipse.jgit.api.Git.open;

public class Main {
	public static void main(String... args) {
		File config = new File("config.json");
		if (!config.exists()) {
			err.println("Config not found!");
			exit(1);
			return;
		}
		File repoFile = new File("./repo/");
		repoFile.delete();
		try {
			JsonObject json = parseString(new String(readAllBytes(config.toPath()))).getAsJsonObject();
			UsernamePasswordCredentialsProvider upassProvider = new UsernamePasswordCredentialsProvider(json.get("username").getAsString(), json.get("password").getAsString());
			CloneCommand cloneCommand = cloneRepository();
			cloneCommand.setURI(json.get("repo").getAsString()).setCredentialsProvider(upassProvider).setDirectory(repoFile).call();
			repoFile.deleteOnExit();
			Git git = open(repoFile);
			Repository repo = git.getRepository();
			new Thread(() -> {
				while (true) {
					try {
						out.println("Fetching");
						git.fetch().setCredentialsProvider(upassProvider).call();
						git.pull().setCredentialsProvider(upassProvider).call();
						git.reset().setMode(ResetCommand.ResetType.HARD).call();
						Iterable<RevCommit> log = git.log().call();
						Iterator<RevCommit> iterator = log.iterator();
						if (iterator.hasNext()) {
							RevCommit commit = iterator.next();
							out.printf("Commit: %s | %s%n", commit.getId(), commit.getAuthorIdent().getName());
							if (commit.getAuthorIdent().getName().equalsIgnoreCase(json.get("name").getAsString())) {
								out.println("Troll");
								NewRevertCommand revertCommand = new NewRevertCommand(repo);//= git.revert();
								revertCommand.include(commit);
								if (json.has("commitTitle"))
									revertCommand.setCustomShortName(json.get("commitTitle").getAsString().replace("%commit-name%", commit.getName()));
								if (json.has("commitMessage"))
									revertCommand.setCustomMessage(json.get("commitMessage").getAsString().replace("%commit-name%", commit.getName()));
								revertCommand.call();
								git.push().setCredentialsProvider(upassProvider).call();
							}
						}
						sleep(5000L);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}).start();

            /*
            GHRepository repository = github.getRepository(json.get("targetrepo").getAsString());
            System.out.println("Recieved repository: " + repository.getName());
            new Thread(() -> {
                for (GHCommit listCommit : repository.listCommits()) {
                    try {
                        if (listCommit.getAuthor().getName().equalsIgnoreCase(json.get("name").getAsString())) {
                            if (json.has("comment"))
                                listCommit.createComment(json.get("comment").getAsString());
                            //undo the config
                            System.out.println("Found commit: " + listCommit.getSHA1());
                            for (GHCommit.File file : listCommit.getFiles()) {

                            }
                            listCommit.
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
                        */
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
