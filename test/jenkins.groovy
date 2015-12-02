test_ec2_k8s_basic = {
    node {
        // git url: 'https://github.com/Juniper/container-networking-ansible.git'
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'k8s-provisioner', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            // create cluster
            sh "ansible-playbook -i localhost playbook.yml --tags=create -e job_id=${env.BUILD_NUMBER}"
        }

        withCredentials([[$class: 'FileBinding', credentialsId: 'k8s.key', variable: 'SSH_PRIVATE_KEY']]) {
            sh("ansible-playbook -i localhost playbook.yml --private-key=$SSH_PRIVATE_KEY --tags=deployer-install")
            sh("ansible-playbook -i localhost playbook.yml --private-key=$SSH_PRIVATE_KEY --tags=workspace")
        }

        // ssh client steps:
        // provision cluster
        // validate
        // example application
        // verify

        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'k8s-provisioner', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            // delete cluster
        }
    }
}

test_noop = {
    node {
        // jenkins-<JOB_NAME>-<BUILD_NUMBER>
        echo env.BUILD_TAG
    }
}

def getTestMatrix() {
    tests = [
        ec2_k8s_basic: test_ec2_k8s_basic,
        noop: test_noop,
    ]
    return tests
}

return this;
