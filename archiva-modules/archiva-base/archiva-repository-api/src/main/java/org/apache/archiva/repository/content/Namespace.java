/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.archiva.repository.content;

import java.util.List;

/**
 * The namespace represents some kind of hierarchical coordinate where artifacts are stored.
 * The syntax of the namespace (e.g. the separator like '.' or '/') is dependent on the repository type.
 *
 * @author Martin Stockhammer <martin_s@apache.org>
 */
public interface Namespace extends ContentItem
{
    String getNamespace( );

    List<String> getNamespacePath( );

}
