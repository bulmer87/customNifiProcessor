/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * This class is a basic utility class that will un marshall all xml that is coming into the flow from the http Post
 * program. The flow file MUST have a fully qualified class name that will be an attribute of the flow file with key
 * fullyQualifiedClassName.
 * It will use the jaxb context factory to create an un marshaller for the class and parse that flow file xml contents
 * into a custom binary array that will be repacked into the outgoing flow file and transferred to the success relationship
 * if parsed and package correctly
 * If for any reason there is a problem parsing the flow files content or there is a missing fully qualified class name
 * as the attribute of the flow file then an exception will be thrown after that flow file is transferred to the failure
 * relationship to be logged
 */
package org.brandon.nifi.test;

import classes.AttributePojo;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.annotation.lifecycle.OnAdded;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.io.InputStreamCallback;

import javax.xml.bind.*;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Tags({"custom"})
@CapabilityDescription("This is a simple default processor that will use jaxb to modify the contents of a flow file")
@SeeAlso({})
@ReadsAttributes({@ReadsAttribute(attribute="fullyQualifiedClassName", description="The full qualified class name sent in from the" +
        "httpPost external program")})
public class TestProcessorOne extends AbstractProcessor {
    private static final String myClassName = "classes.AttributePojo";
    //JAXB context
    private static Unmarshaller myUnmarshaller;

    //logger from the inherited classes
    ComponentLog myLogger;

    //Relationships
    public static final Relationship SUCCESS = new Relationship.Builder()
            .name("SUCCESS")
            .description("If a class is successfully parsed and converted into an IPC message the flow file with the IPC" +
                    " payload will be sent to this relationship for IPC serverconn sends")
            .build();

    public static final Relationship FAILURE = new Relationship.Builder()
            .name("FAILURE")
            .description("If the jaxb context cannot be created or there is a problem processing and translating the " +
                    "class into a IPC message then the flow file with the xml payload will be sent to this relationship")
            .build();

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context){
        //get the logger
        myLogger = getLogger();

        //make the relationships an immutable list
        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(SUCCESS);
        relationships.add(FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @OnAdded
    private void initUmarshaller() throws ClassNotFoundException, JAXBException
    {
        Class clazz = Class.forName(myClassName);
        myUnmarshaller = getJAXBUnMarshaller(clazz);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {

    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        //great not a null flow file expected
        if(flowFile != null)
        {
            final String xmlContents = readContents(flowFile, session);
            StreamSource streamSource = new StreamSource(new StringReader(xmlContents));
            AttributePojo attributePojo = null;
            try
            {
                JAXBElement<AttributePojo> ap = myUnmarshaller.unmarshal(streamSource,AttributePojo.class);
                attributePojo = (AttributePojo) ap.getValue();
            }
            catch(JAXBException unmarshallE)
            {
                throw new ProcessException("TestProcessorOne has thrown a unmarshal exception " + unmarshallE.toString());
            }
            //now process the AttributePojo
            if(attributePojo != null)
            {
                byte[] apBytes = attributePojo.fillBuffer();
            }

        }
        else
        {
            throw new ProcessException("The flow file for processing was a null object and will not be processed");
        }
    }

    private Unmarshaller getJAXBUnMarshaller(Class T) throws JAXBException
    {
        JAXBContext context = JAXBContext.newInstance(T);
        return context.createUnmarshaller();
    }

    private String readContents(final FlowFile ff, final ProcessSession session)
    {
        final AtomicReference<String> contentsRef = new AtomicReference<>(null);
        session.read(ff, new InputStreamCallback()
        {
            @Override
            public void process(InputStream in) throws IOException
            {
                final String contents = IOUtils.toString(in,"UTF-8");
                contentsRef.set(contents);
            }
        });
        return contentsRef.get();
    }

    /**
     * inner factory to manage contexts created by any
     * threads that execute this processor. singleton with
     * a static reference at the processor level
     */
    class JaxbFactory
    {
        private Map<String,JAXBContext>  contextMap;
        private JaxbFactory reference;
        public synchronized JaxbFactory instance()
        {
            if(reference == null)
            {
                reference = new JaxbFactory();
            }
            return reference;
        }
        JaxbFactory()
        {
            contextMap = new HashMap<String,JAXBContext>();
        }
        public synchronized JAXBContext retrieveContext(String className) throws ClassNotFoundException, JAXBException
        {
            if(!contextMap.containsKey(className))
            {
                contextMap.put(className,JAXBContext.newInstance(Class.forName(className)));
            }
            return contextMap.get(className);
        }
    }
}
