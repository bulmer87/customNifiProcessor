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
import org.apache.nifi.processor.util.StandardValidators;
import org.intellij.lang.annotations.Flow;

import javax.xml.bind.*;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Tags({"custom","processor"})
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

    //Property descriptors
    //I think classpath dependencies should not be up to the user of this processor but should be done without any knowledge
    //from those that want to implement this processor but I currently know of no other way to add classpath values to nifi
    //other than dumping jaxb and any other apis into the libs dir of nifi and even then there is no guarantee that it will
    //scan there. Also I dont think that for every processor that we should package jaxb and any other api over and over into
    //that processors nar file. I need to find a way to put external libs on the nifi class path once and make that
    //transparent to the processor user.
    //@TODO find a way to add external classpath to nifi without nar file delivery or making the user input that path
    PropertyDescriptor EXTERNAL_RESOURCE_DIRECTORY = new PropertyDescriptor.Builder()
            .name("Extra Resource Directory")
            .description("This is the external dependencies to directory to make this processor execute in execution")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(true)
            .dynamicallyModifiesClasspath(true)
            .build();

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

        //make the properties an immutable list
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(EXTERNAL_RESOURCE_DIRECTORY);
        this.descriptors = Collections.unmodifiableList(descriptors);

        //make the relationships an immutable set
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
    public void onScheduled(final ProcessContext context)
    {

    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException
    {
        FlowFile flowFile = session.get();
        //great not a null flow file expected
        if(flowFile != null)
        {
            //read the contents of the flow file
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
                sendToFailure(flowFile,session);
                throw new ProcessException("TestProcessorOne has thrown a unmarshal exception " + unmarshallE.toString());
            }
            //now process the AttributePojo
            if(attributePojo != null)
            {
                byte[] apBytes = new byte[0];
                try
                {
                    apBytes = attributePojo.fillBuffer();
                }
                catch (UnsupportedEncodingException e)
                {
                    sendToFailure(flowFile,session);
                    throw new ProcessException("TestProcessorOne has thrown a Unsupported Encoding Exception " + e.toString());
                }
                //now that we have the correct payload we need to send this to a socket processor to send this out
                //but first a new flow file to store the payload and then write the byte array to the flow file
                FlowFile newff = session.get();
                writeContents(apBytes,newff,session);
                sendToSuccess(newff,session);
            }
            else
            {
                sendToFailure(flowFile,session);
                throw new ProcessException("The unmarshalled pojo object is null after processing the flow file xml" +
                        " with id: " + flowFile.getId());
            }

        }
        else
        {
            throw new ProcessException("The flow file for processing was a null object and will not be processed");
        }
    }

    /**
     *
     * @param T
     * @return Unmarshaller
     * @throws JAXBException
     * This is a utility function that will get the jaxb unmarshaller for the class that it is processing
     * from httpPost program
     * Should be part of the base class
     */
    private Unmarshaller getJAXBUnMarshaller(Class T) throws JAXBException
    {
        JAXBContext context = JAXBContext.newInstance(T);
        return context.createUnmarshaller();
    }

    /**
     * @param ff FlowFile
     * @param session ProcessSession
     * @return String XML contents of the flow file
     * A quick and dirty way to read the flow contents into a string.
     * All flow file contents coming into test processors will be a marshalled string
     * of the class contents from an xsd schema
     * I want this to be part of the base class
     */
    private String readContents(final FlowFile ff, final ProcessSession session)
    {
        final AtomicReference<String> contentsRef = new AtomicReference<>(null);
        session.read(ff, in ->
        {
            final String contents = IOUtils.toString(in,"UTF-8");
            contentsRef.set(contents);
        });
        return contentsRef.get();
    }

    /**
     *
     * @param writeArray
     * @param ff
     * @param session
     * Quick function to write the Pojo byte contents to a flow file
     */
    private void writeContents(byte[] writeArray, FlowFile ff ,final ProcessSession session)
    {
        session.write(ff,out ->
        {
            out.write(writeArray);
            out.flush();
        });
    }

    /**
     *
     * @param ff
     * @param session
     * A helper to send the flow file to the failure relationship
     */
    private void sendToFailure(FlowFile ff, ProcessSession session)
    {
        session.transfer(ff,FAILURE);
    }

    /**
     *
     * @param ff
     * @param session
     * A helper to send the newly created flow file to the success relationship
     */
    private void sendToSuccess(FlowFile ff, ProcessSession session)
    {
        session.transfer(ff,SUCCESS);
    }

    /**
     * inner factory to manage contexts created by any
     * threads that execute this processor. singleton with
     * a static reference at the processor level
     * Thread safe
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
