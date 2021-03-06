/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.entity.proxy;

import java.util.Collection;
import java.util.List;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AbstractNonProvisionedControllerTest extends AbstractAbstractControllerTest<TrackingAbstractNonProvisionedController> {

    // TODO Duplication of AbstractControllerTest
    
    private static final Logger log = LoggerFactory.getLogger(AbstractNonProvisionedControllerTest.class);
    
    @Override
    protected TrackingAbstractNonProvisionedController newController() {
        return app.addChild(EntitySpec.create(TrackingAbstractNonProvisionedController.class)
                .configure("serverPool", cluster) 
                .configure("portNumberSensor", WebServerEntity.HTTP_PORT)
                .configure("domain", "mydomain"));
    }
    
    @Override
    protected List<Collection<String>> getUpdates(TrackingAbstractNonProvisionedController controller) {
        return controller.getUpdates();
    }
}
