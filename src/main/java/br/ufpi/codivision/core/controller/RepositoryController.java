/**
 * 
 */
package br.ufpi.codivision.core.controller;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import br.com.caelum.vraptor.Controller;
import br.com.caelum.vraptor.Get;
import br.com.caelum.vraptor.Post;
import br.com.caelum.vraptor.Result;
import br.com.caelum.vraptor.validator.Severity;
import br.com.caelum.vraptor.validator.SimpleMessage;
import br.com.caelum.vraptor.view.Results;
import br.ufpi.codivision.common.annotation.Permission;
import br.ufpi.codivision.common.annotation.Public;
import br.ufpi.codivision.common.security.UserSession;
import br.ufpi.codivision.core.dao.ConfigurationDAO;
import br.ufpi.codivision.core.dao.FileDAO;
import br.ufpi.codivision.core.dao.RepositoryDAO;
import br.ufpi.codivision.core.dao.UserDAO;
import br.ufpi.codivision.core.dao.UserRepositoryDAO;
import br.ufpi.codivision.core.extractor.model.Extraction;
import br.ufpi.codivision.core.extractor.model.ExtractionType;
import br.ufpi.codivision.core.extractor.model.RepositoryCredentials;
import br.ufpi.codivision.core.extractor.service.TaskService;
import br.ufpi.codivision.core.model.Configuration;
import br.ufpi.codivision.core.model.DirTree;
import br.ufpi.codivision.core.model.ExtractionPath;
import br.ufpi.codivision.core.model.Repository;
import br.ufpi.codivision.core.model.Revision;
import br.ufpi.codivision.core.model.TestFile;
import br.ufpi.codivision.core.model.User;
import br.ufpi.codivision.core.model.UserRepository;
import br.ufpi.codivision.core.model.enums.NodeType;
import br.ufpi.codivision.core.model.enums.PermissionType;
import br.ufpi.codivision.core.model.enums.RepositoryType;
import br.ufpi.codivision.core.model.enums.TimeWindow;
import br.ufpi.codivision.core.model.validator.ConfigurationValidator;
import br.ufpi.codivision.core.model.validator.RepositoryValidator;
import br.ufpi.codivision.core.model.vo.AuthorPercentage;
import br.ufpi.codivision.core.model.vo.LineChart;
import br.ufpi.codivision.core.model.vo.RepositoryVO;
import br.ufpi.codivision.core.repository.GitUtil;
import br.ufpi.codivision.core.util.QuickSort;
import br.ufpi.codivision.debit.codesmell.CodeSmellID;
import br.ufpi.codivision.debit.model.CodeSmell;
import br.ufpi.codivision.debit.model.CodeSmellMethod;
import br.ufpi.codivision.debit.model.File;
import br.ufpi.codivision.debit.model.Method;
import weka.clusterers.SimpleKMeans;
import weka.core.Instances;

/**
 * @author Werney Ayala
 *
 */
@Controller
public class RepositoryController {

	@Inject private Result result;
	@Inject private UserSession userSession;

	@Inject private RepositoryDAO dao;
	@Inject private UserDAO userDAO;
	@Inject private UserRepositoryDAO userRepositoryDAO;
	@Inject private ConfigurationDAO configurationDAO;
	@Inject private FileDAO fileDAO;
	

	@Inject private RepositoryValidator validator;
	@Inject private ConfigurationValidator configurationValidator;

	@Inject private TaskService taskService;


	private final Logger log = LoggerFactory.getLogger(getClass());

	@Permission(PermissionType.MEMBER)
	@Get("/repository/{repositoryId}")
	public void show(Long repositoryId) {

		Repository repository = dao.findById(repositoryId);
		RepositoryVO repositoryVO = new RepositoryVO(repository);
		List<RepositoryVO> repositoryList = dao.listMyRepositories(userSession.getUser().getId());
		List<UserRepository> userRepositoryList = userRepositoryDAO.listByRepositoryId(repositoryId);

		result.include("repository", repositoryVO);
		result.include("repositoryList", repositoryList);
		result.include("userRepositoryList", userRepositoryList);

	}

	@Get
	public void list(){
		List<RepositoryVO> repositoryList = dao.listMyRepositories(userSession.getUser().getId());
		String urlImage = userSession.getUser().getGravatarImageUrl() + "?s=217";
		//List<RepositoryType> types = new ArrayList<RepositoryType>(Arrays.asList(RepositoryType.values()));
		List<RepositoryType> types = new ArrayList<RepositoryType>();
		types.add(RepositoryType.GIT);
		result.include("urlImage", urlImage);
		result.include("types", types);
		result.include("repositoryList", repositoryList);
		//TODO
		if(taskService.getTaskQueue().size()!=0) {
			result.include("notice", new SimpleMessage("success", "repository.update.message", Severity.INFO));
		}
	}

	@Post("/repository/add")
	public void add(String url, String branch, boolean local, String login, String password){

		Configuration configuration = new Configuration();
		configuration.setAddWeight(1.0);
		configuration.setModWeight(1.0);
		configuration.setDelWeight(0.5);
		configuration.setConditionWeight(1.0);
		configuration.setChangeDegradation(5);
		configuration.setMonthlyDegradation(0);
		configuration.setAlertThreshold(60);
		configuration.setExistenceThreshold(80);
		configuration.setTruckFactorThreshold(50);
		configuration.setTimeWindow(TimeWindow.EVER);

		Repository repository = new Repository();

		//para repositorios do git lab, deve ser adicionado o .git no final.
		if(url.contains(".git")) {
			repository.setName(url.split("/")[url.split("/").length - 1].split("[.]")[0]);
		}else {
			repository.setName(url.split("/")[url.split("/").length-1]);
		}
		repository.setUrl(url);
		repository.setRepositoryRoot(url);
		repository.setConfiguration(configuration);
		repository.setType(RepositoryType.GIT);

		ExtractionPath path = new ExtractionPath();
		path.setPath("/"+branch);

		repository.setExtractionPath(path);

		try {
			if(login == null && password == null) {
				GitUtil.testInfoRepository(repository.getUrl(), path.getPath().substring(1));
			} else
				GitUtil.testInfoRepository(repository.getUrl(), path.getPath().substring(1), login, password);	

			repository = dao.save(repository);

			User user = userDAO.findById(userSession.getUser().getId());

			UserRepository permission = new UserRepository();
			permission.setPermission(PermissionType.OWNER);
			permission.setRepository(repository);
			permission.setUser(user);
			userRepositoryDAO.save(permission);

			Extraction extraction = new Extraction(repository.getId(),
					ExtractionType.REPOSITORY,
					new RepositoryCredentials(login, password));

			taskService.addTask(extraction);

			result.use(Results.json()).withoutRoot().from("").recursive().serialize();

		} catch (Exception e) {
			result.use(Results.json()).withoutRoot().from(e.getMessage()).recursive().serialize();
		}

	}
	/**
	 * This method modifies the name of a repository
	 * @param repositoryId - Id repository to be modified
	 * @param repositoryName - New repository name to be saved
	 */
	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/edit")
	public void edit(Long repositoryId,String repositoryName){

		Repository repository = dao.findById(repositoryId);
		repository.setName(repositoryName);
		dao.save(repository);
		result.redirectTo(this).show(repositoryId);

	}

	/**
	 * This method is responsible for deleting a repository
	 * @param repositoryId - Id of the repository to be deleted
	 */
	@Permission(PermissionType.MEMBER)
	@Get("/repository/{repositoryId}/delete")
	public void delete(Long repositoryId){

		Repository repository = dao.findById(repositoryId);
		UserRepository ur = userRepositoryDAO.findByIds(userSession.getUser().getId(), repositoryId);

		if(ur.getPermission() == PermissionType.OWNER){
			repository.setDeleted(true);
			dao.save(repository);

		} else {
			userRepositoryDAO.delete(ur.getId());		
		}

		result.include("notice", new SimpleMessage("success", "repository.delete.success", Severity.INFO));
		result.redirectTo(this).list();
	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/configuration")
	public void config(Long repositoryId, Configuration configuration) {

		configurationValidator.validate(configuration);
		configurationValidator.onErrorRedirectTo(this.getClass()).chart(repositoryId);

		Configuration config = dao.getConfiguration(repositoryId);
		configuration.setId(config.getId());

		configurationDAO.save(configuration);

		result.redirectTo(this).chart(repositoryId);
	}

	@Permission(PermissionType.MEMBER)
	@Get("/repository/{repositoryId}/chart")
	public void chart(Long repositoryId) {

		Repository repository = dao.findById(repositoryId);
		RepositoryVO repositoryVO = new RepositoryVO(repository);

		ExtractionPath extractionPath = repository.getExtractionPath();

		Configuration configuration = repository.getConfiguration();
		configuration.refreshTime();

		List<TimeWindow> windows = new ArrayList<TimeWindow>(Arrays.asList(TimeWindow.values()));

		result.include("windows", windows);
		result.include("configuration", configuration);
		result.include("repository", repositoryVO);
		result.include("extractionPath", extractionPath);

	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/alterations")
	public void getAlterations(Long repositoryId, String newPath){

		Repository repository = dao.findById(repositoryId);

		if(repository.getType()==RepositoryType.SVN && newPath.equals("/")){
			newPath = repository.getExtractionPath().getPath();
		}
		//referente ao /master
		if(repository.getType()==RepositoryType.GITHUB || repository.getType()==RepositoryType.GIT){
			if(!newPath.equals("/")) 
				newPath = newPath.substring(repository.getExtractionPath().getPath().length());
		}
		List<AuthorPercentage> percentage = dao.getPercentage(repositoryId,repository.getUrl().substring(repository.getRepositoryRoot().length())+newPath);
		result.use(Results.json()).withoutRoot().from(percentage).recursive().serialize();
	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/update")
	public void update(Long repositoryId) {

		validator.canUpdate(repositoryId);
		validator.onErrorRedirectTo(this.getClass()).show(repositoryId);

		//TODO PARA ATUALIZAR

		result.include("notice", new SimpleMessage("info", "repository.update.message", Severity.INFO));
		result.redirectTo(this).show(repositoryId);

	}


	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/testConfiguration")
	public void testConfig(Long repositoryId, Configuration configuration) {

		Configuration config = dao.getConfiguration(repositoryId);


		if(configuration.getTimeWindow()!=null){
			if(configuration.getTimeWindow()==TimeWindow.CUSTOM){
				if(configuration.getEndDate()!=null && configuration.getInitDate()!=null){
					config.setTimeWindow(TimeWindow.CUSTOM);
					config.setEndDate(configuration.getEndDate());
					config.setInitDate(configuration.getInitDate());
					configurationDAO.save(config);
				}else if(configuration.getEndDate()!=null){
					Date date = new Date(configuration.getEndDate().getTime() + (24 * 60 * 60 * 1000));
					config.setTimeWindow(TimeWindow.CUSTOM);
					config.setEndDate(date);
					config.setInitDate(configuration.getEndDate());
					configurationDAO.save(config);
				}else if(configuration.getInitDate()!=null){
					config.setTimeWindow(TimeWindow.CUSTOM);
					Date date = new Date(configuration.getInitDate().getTime() + (24 * 60 * 60 * 1000));
					config.setEndDate(date);
					config.setInitDate(configuration.getInitDate());
					configurationDAO.save(config);
				}

			}else{

				config.setTimeWindow(configuration.getTimeWindow());
				config.refreshTime();
				configurationDAO.save(config);
			}
		}else{
			config.refreshTime();
		}

		result.redirectTo(this).testInformation(repositoryId);
	}


	@Permission(PermissionType.MEMBER)
	@Get("/repository/{repositoryId}/test/information")
	public void testInformation(Long repositoryId) {

		Repository repository = dao.findById(repositoryId);

		RepositoryVO repositoryVO = new RepositoryVO(repository);

		ExtractionPath extractionPath = repository.getExtractionPath();

		Configuration configuration = repository.getConfiguration();
		configuration.refreshTime();

		List<TimeWindow> windows = new ArrayList<TimeWindow>(Arrays.asList(TimeWindow.values()));

		result.include("testFiles",repository.getTestFiles());
		result.include("windows", windows);
		result.include("configuration", configuration);
		result.include("repository", repositoryVO);
		result.include("extractionPath", extractionPath);

	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/authors")
	public void getAuthors(Long repositoryId) {

		result.use(Results.json()).withoutRoot().from(dao.getAuthors(repositoryId)).recursive().serialize();
	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/rankingDevelops")
	public void getRankingDevelops(Long repositoryId) {

		LineChart chart = dao.getContribuitionQntLine(repositoryId, "/");

		List<AuthorPercentage> list = new ArrayList<AuthorPercentage>();
		for(int i = 0; i<chart.getDataCategories().length; i++){
			AuthorPercentage author = new AuthorPercentage(chart.getDataCategories()[i], (double) chart.getDataSeries().get(0).getData()[i], chart.getDataSeries().get(1).getData()[i]);
			list.add(author);
		}

		QuickSort.sort2(list);

		result.use(Results.json()).withoutRoot().from(list).recursive().serialize();
	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/authorHistoric")
	public void getAuthorHistoric(Long repositoryId, String author) {

		LineChart chart = new LineChart();
		chart = dao.getTestCommitsHistoryAuthor(repositoryId, author);
		result.use(Results.json()).withoutRoot().from(chart).recursive().serialize();
	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/projectHistoric")
	public void getProjectHistoric(Long repositoryId) {

		LineChart chart = new LineChart();
		chart = dao.getTestCommitsHistory(repositoryId);
		result.use(Results.json()).withoutRoot().from(chart).recursive().serialize();
	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/alterationsQntLine")
	public void getAlterationsQntLine(Long repositoryId, String newPath){

		Repository repository = dao.findById(repositoryId);

		if(repository.getType()==RepositoryType.SVN && newPath.equals("/")){
			newPath = repository.getExtractionPath().getPath();
		}
		//referente ao /master
		if(repository.getType()==RepositoryType.GITHUB || repository.getType()==RepositoryType.GIT){
			if(!newPath.equals("/"))
				newPath = newPath.substring(repository.getExtractionPath().getPath().length());
		}
		LineChart percentage = dao.getContribuitionQntLine(repositoryId, repository.getUrl().substring(repository.getRepositoryRoot().length())+newPath);
		result.use(Results.json()).withoutRoot().from(percentage).recursive().serialize();


	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/percentageContribuition")
	public void getAlterationsQntLine(Long repositoryId){

		result.use(Results.json()).withoutRoot().from(dao.getPercentageContribuition(repositoryId)).recursive().serialize();

	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/testFile")
	public void getTestFiles(Long repositoryId) {

		result.use(Results.json()).withoutRoot().from(dao.findById(repositoryId).getTestFiles()).recursive().serialize();
	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/addTestPath")
	public void addTestPath(Long repositoryId, String newPathTest) {
		Repository repository = dao.findById(repositoryId);
		for(TestFile file: repository.getTestFiles()){
			if(file.getPath().equals(newPathTest)){
				result.use(Results.json()).withoutRoot().from("").recursive().serialize();
				return;
			}
		}

		TestFile file = new TestFile();
		file.setPath(newPathTest);
		repository.getTestFiles().add(file);
		dao.save(repository);

		result.use(Results.json()).withoutRoot().from("").recursive().serialize();
		return;
	}

	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/deleteTestPath")
	public void deleteTestPath(Long repositoryId, int pathId) {
		Repository repository = dao.findById(repositoryId);

		repository.getTestFiles().remove(pathId);

		dao.save(repository);

		result.use(Results.json()).withoutRoot().from("").recursive().serialize();

	}

	@Public
	@Post("/repository/remoteUpdate")
	public void remoteUpdate(String repository) {
		Gson json = new Gson();
		Repository repositoryUpdate = json.fromJson(repository, Repository.class);
		Repository repositoryCurrent = dao.findByUrl(repositoryUpdate.getUrl());
		if(repositoryCurrent==null){
			result.use(Results.json()).withoutRoot().from("Repositorio inexistente").recursive().serialize();
		}else{

			repositoryCurrent.setRepositoryRoot(repositoryUpdate.getRepositoryRoot());


			repositoryCurrent.getExtractionPath().setDirTree(repositoryUpdate.getExtractionPath().getDirTree());

			List<Revision> newRevision = new ArrayList<Revision>();

			for(int i = 0; i < repositoryUpdate.getRevisions().size(); i++){
				if(repositoryCurrent.getLastUpdate() == null){
					newRevision.add(repositoryUpdate.getRevisions().get(i));
				}else if(repositoryUpdate.getRevisions().get(i).getDate().getTime() > repositoryCurrent.getLastUpdate().getTime()){
					newRevision.add(repositoryUpdate.getRevisions().get(i));
				}
			}

			repositoryCurrent.setLastRevision(repositoryUpdate.getLastRevision());
			repositoryCurrent.getRevisions().addAll(newRevision);
			repositoryCurrent.setLastUpdate(repositoryUpdate.getLastUpdate());

			for(TestFile file:repositoryUpdate.getTestFiles()){
				boolean check = false;
				for(TestFile file2:repositoryCurrent.getTestFiles()){
					if(file.getPath().equals(file2.getPath())){
						check = true;
					}
				}
				if(!check){
					repositoryCurrent.getTestFiles().add(file);
				}
			}

			dao.save(repositoryCurrent);

			result.use(Results.json()).withoutRoot().from("Repositorio atualizado").recursive().serialize();
		}

	}

	@Permission(PermissionType.MEMBER)
	@Get("/repository/{repositoryId}/technicalDebit")
	public void infoTD(Long repositoryId) throws Exception {
		
		Repository repository = dao.findById(repositoryId);
		RepositoryVO repositoryVO = new RepositoryVO(repository);

		Configuration configuration = repository.getConfiguration();
		configuration.refreshTime();

		List<TimeWindow> windows = new ArrayList<TimeWindow>(Arrays.asList(TimeWindow.values()));

		result.include("windows", windows);
		result.include("configuration", configuration);
		result.include("repository", repositoryVO);

	}


	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/td")
	public void getTD(Long repositoryId, String fileName){

		Repository repository = dao.findById(repositoryId);

		if(!fileName.equals("/") && !fileName.equals("/".concat(repository.getName())) ) {
			String[] split = fileName.split("/");
			String name = split[split.length - 1];

			for (File file : repository.getCodeSmallsFile()) {
				String[] split2 = file.getPath().split("/");
				String name_file = split2[split2.length - 1];

				if(name.equals(name_file)) {
					result.use(Results.json()).withoutRoot().from(file).recursive().serialize();
					return;
				}

			}


		}else {
			result.use(Results.json()).withoutRoot().from(repository.getCodeSmallsFile().size()).recursive().serialize();
		}



	}
	
	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/file/{fileId}/method/td")
	public void getTDMethod(Long repositoryId, Long fileId, String methodName){

		File file = fileDAO.findById(fileId);
		
		for (Method method : file.getMethods()) {
			if(method.getName().equals(methodName)) {
				result.use(Results.json()).withoutRoot().from(method).recursive().serialize();
				return;
			}
				
		}
	}
	
	public static BufferedReader readDataFile(String filename) {
		BufferedReader inputReader = null;
 
		try {
			inputReader = new BufferedReader(new FileReader(filename));
		} catch (FileNotFoundException ex) {
			System.err.println("File not found: " + filename);
		}
 
		return inputReader;
	}
	
	public int[] calculaClusters(Long repositoryId) throws Exception {
		SimpleKMeans kmeans = new SimpleKMeans();
 
		kmeans.setSeed(10);
 
		//important parameter to set: preserver order, number of cluster.
		kmeans.setPreserveInstancesOrder(true);	
		kmeans.setNumClusters(3);
 
		BufferedReader datafile = readDataFile(GitUtil.getDirectoryToSave().concat("file"+repositoryId+".arff")); 
		Instances data = new Instances(datafile);
 
 
		kmeans.buildClusterer(data);
 
		// This array returns the cluster number (starting with 0) for each instance
		// The array has as many elements as the number of instances
		int[] assignments = kmeans.getAssignments();
 
//		int i=0;
//		for(int clusterNum : assignments) {
//		    System.out.printf("Instance %d -> Cluster %d \n", i, clusterNum);
//		    i++;
//		}
		
		return assignments;
	}
	
	
	@Permission(PermissionType.MEMBER)
	@Post("/repository/{repositoryId}/tree/td")
	public void getDirTreeTD(Long repositoryId) throws Exception{
		Repository repository = dao.findById(repositoryId);
		
		
		ArrayList<File> fileWithTD = new ArrayList<File>();
		
		for (File file : repository.getCodeSmallsFile()) {
			
			boolean teste = false;
			
			if(file.getCodeSmells().isEmpty() && file.getQntBadSmellComment() == 0) {
				for (Method method : file.getMethods()) {
					if(!method.getCodeSmells().isEmpty()) {
						teste = true;
					}
				}
			}else {
				teste = true;
			}
			
			if(teste) {
				
				fileWithTD.add(file);
			}
			
			
		}

		String arff = "@relation dt\n" + 
				"\n" + 
				"@attribute brainclass numeric\n" + 
				"@attribute brainmethod numeric\n" + 
				"@attribute complexmethod numeric\n" + 
				"@attribute godclass numeric\n" + 
				"@attribute longmethod numeric\n" + 
				"@attribute dataclass numeric\n" + 
				"@attribute featureenvy numeric\n" + 
				"@attribute codesmellcomments numeric\n" + 
				"\n" + 
				"@data\n";

		

		for (File file : fileWithTD) {
			int[] list = new int[7];
			for (CodeSmell codeSmell : file.getCodeSmells()) {
				if(codeSmell.getCodeSmellType().equals(CodeSmellID.BRAIN_CLASS)) {
					list[0] = list[0] + 1;
				}
				if(codeSmell.getCodeSmellType().equals(CodeSmellID.BRAIN_METHOD)) {
					list[1] = list[1] + 1;
				}
				if(codeSmell.getCodeSmellType().equals(CodeSmellID.COMPLEX_METHOD)) {
					list[2] = list[2] + 1;
				}
				if(codeSmell.getCodeSmellType().equals(CodeSmellID.GOD_CLASS)) {
					list[3] = list[3] + 1;
				}
				if(codeSmell.getCodeSmellType().equals(CodeSmellID.LONG_METHOD)) {
					list[4] = list[4] + 1;
				}
				if(codeSmell.getCodeSmellType().equals(CodeSmellID.DATA_CLASS)) {
					list[5] = list[5] + 1;
				}
				if(codeSmell.getCodeSmellType().equals(CodeSmellID.FEATURE_ENVY)) {
					list[6] = list[6] + 1;
				}
			}
			
			for (Method method : file.getMethods()) {
				for (CodeSmellMethod codeSmellMethod : method.getCodeSmells()) {
					if(codeSmellMethod.getCodeSmellType().equals(CodeSmellID.BRAIN_CLASS)) {
						list[0] = list[0] + 1;
					}
					if(codeSmellMethod.getCodeSmellType().equals(CodeSmellID.BRAIN_METHOD)) {
						list[1] = list[1] + 1;
					}
					if(codeSmellMethod.getCodeSmellType().equals(CodeSmellID.COMPLEX_METHOD)) {
						list[2] = list[2] + 1;
					}
					if(codeSmellMethod.getCodeSmellType().equals(CodeSmellID.GOD_CLASS)) {
						list[3] = list[3] + 1;
					}
					if(codeSmellMethod.getCodeSmellType().equals(CodeSmellID.LONG_METHOD)) {
						list[4] = list[4] + 1;
					}
					if(codeSmellMethod.getCodeSmellType().equals(CodeSmellID.DATA_CLASS)) {
						list[5] = list[5] + 1;
					}
					if(codeSmellMethod.getCodeSmellType().equals(CodeSmellID.FEATURE_ENVY)) {
						list[6] = list[6] + 1;
					}
				}
			}
			
			
			arff = arff +list[0]+","+list[1]+ ","+list[2]+","+list[3]+ ","+list[4]+","+list[5]+ ","+list[6]+","+file.getQntBadSmellComment()+"\n";			
			
		}
		
		PrintWriter writer = new PrintWriter(GitUtil.getDirectoryToSave().concat("file"+repositoryId+".arff"), "UTF-8");
		writer.println(arff);
		writer.close();
		
		int[] clusters = calculaClusters(repositoryId);
		
		
		DirTree c1 = new DirTree();
		c1.setText("Cluster 1");
		c1.setType(NodeType.FOLDER);
		
		DirTree c2 = new DirTree();
		c2.setText("Cluster 2");
		c2.setType(NodeType.FOLDER);
		
		DirTree c3 = new DirTree();
		c3.setText("Cluster 3");
		c3.setType(NodeType.FOLDER);
		
		int i = 0;
		for (int clusterNum : clusters) {
			
			if(clusterNum == 0) {
				File file = fileWithTD.get(i);
				
				DirTree dirTree = new DirTree();
				dirTree.setType(NodeType.FILE);
				
				//pega a ultima porcao do nome
				String[] split = file.getPath().split("/");
				String name = split[split.length - 1];
				
				dirTree.setText(name);
				
				c1.getChildren().add(dirTree);
				
			}
			if(clusterNum == 1) {
				
				File file = fileWithTD.get(i);
				
				DirTree dirTree = new DirTree();
				dirTree.setType(NodeType.FILE);
				
				//pega a ultima porcao do nome
				String[] split = file.getPath().split("/");
				String name = split[split.length - 1];
				
				dirTree.setText(name);
				
				c2.getChildren().add(dirTree);
				
			}
			if(clusterNum == 2) {
				
				File file = fileWithTD.get(i);
				
				DirTree dirTree = new DirTree();
				dirTree.setType(NodeType.FILE);
				
				//pega a ultima porcao do nome
				String[] split = file.getPath().split("/");
				String name = split[split.length - 1];
				
				dirTree.setText(name);
				
				c3.getChildren().add(dirTree);
				
			}
			
			
			i++;
		}
		
		DirTree tree = new DirTree();
		tree.setType(NodeType.FOLDER);
		tree.setText(repository.getName());
		
		tree.getChildren().add(c1);
		tree.getChildren().add(c2);
		tree.getChildren().add(c3);
		
		result.use(Results.json()).withoutRoot().from(tree).recursive().serialize();
		

	}


}