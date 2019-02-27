package imagetagresource

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/manifest\?tagUrl=\/\w+\/\w+\/\d+/),
        test('/manifest?tagUrl=/no_skatteetaten_aurora_demo/whoami/1')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/ImageTagResource.json'))
  }
}