package imagetagresource

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'POST'
    url $(
        stub(~/\/manifest/),
        test('/manifest')
    )
    headers {
      contentType(applicationJson())
    }
    body('["docker1.no/no_skatt_test/namespace/name/tag"]')
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/ImageTagResource.json'))
  }
}