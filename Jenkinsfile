def tests

node {
    checkout scm
    def script = load 'test/jenkins.groovy'
    tests = script.getTestMatrix()
}

parallel tests
