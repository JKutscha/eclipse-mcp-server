pipeline {
	options {
		timeout(time: 30, unit: 'MINUTES')
		buildDiscarder(logRotator(numToKeepStr: '10'))
		disableConcurrentBuilds(abortPrevious: true)
		timestamps()
	}
	agent {
		label 'ubuntu-latest'
	}
	tools {
		maven 'apache-maven-latest'
		// JavaSE-25 is the BREE of every bundle here, so an older JDK does not compile
		jdk 'temurin-jdk25-latest'
	}
	stages {
		stage('Build and test') {
			steps {
				// deliberately not wrapped in xvnc: the suite runs with no DISPLAY at
				// all, verified rather than assumed. useUIHarness is false and every
				// UI tool refuses cleanly without a workbench, so a display would only
				// hide a tool that had started needing one
				sh '''
					mvn clean verify \
						-Dmaven.repo.local=$WORKSPACE/.m2/repository \
						--batch-mode --no-transfer-progress --show-version --errors
				'''
			}
		}
	}
	post {
		always {
			// no -Dmaven.test.failure.ignore: a red build is the point of this job,
			// and this repository has already shipped a suite that ran zero tests
			// while reporting success
			junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml'
			// the test Eclipse writes its own log, and a failure that is not in the
			// surefire report is usually explained there
			archiveArtifacts artifacts: 'tests/*/target/work/data/.metadata/*.log', allowEmptyArchive: true
		}
		success {
			// what a build actually produces: an installable p2 repository
			archiveArtifacts artifacts: 'update-site/*/target/*.zip', allowEmptyArchive: false
		}
	}
}
