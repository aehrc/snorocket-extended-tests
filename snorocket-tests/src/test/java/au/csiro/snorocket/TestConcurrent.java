/**
 * Copyright CSIRO Australian e-Health Research Centre (http://aehrc.com).
 * All rights reserved. Use is subject to license terms and conditions.
 */
package au.csiro.snorocket;

import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import au.csiro.ontology.Node;
import au.csiro.ontology.Ontology;
import au.csiro.ontology.importer.rf2.RF2Importer;
import au.csiro.ontology.importer.rf2.RelationshipRow;
import au.csiro.ontology.input.Inputs;
import au.csiro.ontology.input.ModuleInfo;
import au.csiro.ontology.input.RF2Input;
import au.csiro.ontology.input.Version;
import au.csiro.ontology.model.Axiom;
import au.csiro.ontology.model.NamedConcept;
import au.csiro.ontology.util.NullProgressMonitor;
import au.csiro.snorocket.core.CoreFactory;
import au.csiro.snorocket.core.IFactory;
import au.csiro.snorocket.core.NormalisedOntology;
import au.csiro.snorocket.owlapi.SnorocketOWLReasoner;
import au.csiro.snorocket.owlapi.util.DebugUtils;
import junit.framework.Assert;

/**
 * @author Alejandro Metke
 *
 */
public class TestConcurrent {
    
    @Test
    public void testConcurrentRF2() {
        InputStream snomed = this.getClass().getResourceAsStream("/config-snomed.xml");
        InputStream amt = this.getClass().getResourceAsStream("/amt_v3_owl/amtv3.owl");
        InputStream amtInferred = this.getClass().getResourceAsStream("/amt_v3_owl/amtv3_inferred.owl");
        
        RunnableClassification c1 = new RunnableClassification(amt, amtInferred, "AMT OWL");
        RunnableRF2Classification c2 = new RunnableRF2Classification(snomed, "20130131", "SNOMED CT");
        
        Thread t1 = new Thread(c1);
        Thread t2 = new Thread(c2);
        
        t1.start();
        t2.start();
        
        try { t1.join(); } catch(InterruptedException e) { e.printStackTrace(); }
        try { t2.join(); } catch(InterruptedException e) { e.printStackTrace(); }
    }
    
    class RunnableClassification implements Runnable {
        private InputStream stated;
        private InputStream inferred;
        private String name;
        
        RunnableClassification(InputStream stated, InputStream inferred, String name){
            this.stated = stated;
            this.inferred = inferred;
            this.name = name;
        }
        
        public void run() {
            try {
                System.out.println("Classifying " + name);
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                OWLOntology ont = manager.loadOntologyFromOntologyDocument(stated);
                // Classify ontology from stated form
                SnorocketOWLReasoner c = new SnorocketOWLReasoner(ont, null, false);

                c.precomputeInferences(InferenceType.CLASS_HIERARCHY);
                
                // Load ontology from inferred form
                System.out.println("Loading inferred ontology");
                OWLOntologyManager manager2 = OWLManager.createOWLOntologyManager();
                OWLOntology ont2 = manager2.loadOntologyFromOntologyDocument(inferred);

                OWLReasoner reasoner2 = new StructuralReasonerFactory().createReasoner(ont2);
                reasoner2.precomputeInferences(InferenceType.CLASS_HIERARCHY);

                System.out.println("Testing parent equality");
                int numOk = 0;
                int numWrong = 0;
                for (OWLClass cl : ont2.getClassesInSignature()) {

                    // Ignore owl:nothing - some generated inferred files do not
                    // connect childless nodes to owl:nothing
                    if (cl.toStringID().equals("http://www.w3.org/2002/07/owl#Nothing"))
                        continue;

                    Set<OWLClass> truth = new HashSet<OWLClass>();

                    Set<OWLClass> parents = reasoner2.getSuperClasses(cl, true).getFlattened();
                    for (OWLClass ocl : parents) {
                        if (!ocl.isTopEntity()) {
                            truth.add(ocl);
                        }
                    }

                    Set<OWLClass> classified = new HashSet<OWLClass>();
                    NodeSet<OWLClass> otherParents = c.getSuperClasses(cl, true);
                    classified.addAll(otherParents.getFlattened());
                    
                    // Remove top if present
                    classified.remove(ont.getOWLOntologyManager().getOWLDataFactory().getOWLThing());

                    // Assert parents are equal
                    if (truth.size() != classified.size()) {
                        numWrong++;
                        System.out.println(cl.toStringID() + "("
                                + DebugUtils.getLabel(cl, ont) + ")");
                        System.out.println("Truth: " + formatClassSet(truth, ont));
                        System.out.println("Classified: " + formatClassSet(classified, ont));
                    } else {
                        truth.removeAll(classified);

                        if (truth.isEmpty()) {
                            numOk++;
                        } else {
                            numWrong++;
                            System.out.println(cl.toStringID() + "(" + DebugUtils.getLabel(cl, ont) + ")");
                            System.out.println("Truth: " + formatClassSet(truth, ont));
                            System.out.println("Classified: " + formatClassSet(classified, ont));
                        }
                    }
                }
                assertTrue("Num OK: " + numOk + " Num wrong: " + numWrong, numWrong == 0);
                
                System.out.println("Done");
            } catch (Exception e) {
                e.printStackTrace();
                Assert.assertTrue(false);
            }
        }
        
        protected String formatClassSet(Set<OWLClass> set, OWLOntology ont) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (OWLClass c : set) {
                sb.append(c.toStringID());
                sb.append("(");
                sb.append(DebugUtils.getLabel(c, ont));
                sb.append(") ");
            }
            sb.append("]");
            return sb.toString();
        }

     }
    
    class RunnableRF2Classification implements Runnable {
        
        private InputStream config;
        private String version;
        private String name;
        
        RunnableRF2Classification(InputStream config, String version, String name) {
            this.config = config;
            this.version = version;
            this.name = name;
        }
        
        @Override
        public void run() {
            try {
                System.out.println("Classifying " + name);
                Inputs in = Inputs.load(config);
                
                // Classify ontology from stated form
                System.out.println("Classifying ontology");
                IFactory factory = new CoreFactory();
                NormalisedOntology no = new NormalisedOntology(factory);
                System.out.println("Importing axioms");
                
                RF2Importer imp = new RF2Importer(in.getInputs());
                Iterator<Ontology> it = imp.getOntologyVersions(new NullProgressMonitor());
                
                while(it.hasNext()) {
                    Ontology ont = it.next();
                    if(ont.getVersion().equals(version)) {
                        System.out.println("Loading axioms");
                        no.loadAxioms(new HashSet<Axiom>((Collection<? extends Axiom>) ont.getStatedAxioms()));
                        System.out.println("Running classification");
                        no.classify();
                        System.out.println("Computing taxonomy");
                        no.buildTaxonomy();
                        System.out.println("Done");
                        
                        System.gc();

                        RF2Input rf2In = (RF2Input) in.getInputs().get(0);
                        ModuleInfo modInfo = rf2In.getModules().get(0);
                        Version ver = modInfo.getVersions().get(0);
                        InputStream canonical = this.getClass().getResourceAsStream(
                                rf2In.getRelationshipsFiles().iterator().next());
                        
                        System.out.println("Comparing with canonical ontology");
                        String isAId = ver.getMetadata().get("isAId");
                        List<String> problems = new ArrayList<String>();
                        
                        System.out.println("Loading rows from canonical table");
                        Map<String, List<RelationshipRow>> allRows = new HashMap<String, List<RelationshipRow>>();
                        BufferedReader br = null;
                        try {
                            br = new BufferedReader(new InputStreamReader(canonical));
                            String line = br.readLine(); // skip first line
                            while (null != (line = br.readLine())) {
                                if (line.trim().length() < 1) {
                                    continue;
                                }
                                int idx1 = line.indexOf('\t');
                                int idx2 = line.indexOf('\t', idx1 + 1);
                                int idx3 = line.indexOf('\t', idx2 + 1);
                                int idx4 = line.indexOf('\t', idx3 + 1);
                                int idx5 = line.indexOf('\t', idx4 + 1);
                                int idx6 = line.indexOf('\t', idx5 + 1);
                                int idx7 = line.indexOf('\t', idx6 + 1);
                                int idx8 = line.indexOf('\t', idx7 + 1);
                                int idx9 = line.indexOf('\t', idx8 + 1);

                                if (idx1 < 0 || idx2 < 0 || idx3 < 0 || idx4 < 0 || 
                                        idx5 < 0 || idx6 < 0 || idx7 < 0 || idx8 < 0 || 
                                        idx9 < 0) {
                                    throw new RuntimeException("Concepts: Mis-formatted line, expected 10 "
                                            + "tab-separated fields, got: " + line);
                                }
                                
                                final String id = line.substring(0, idx1);
                                final String effectiveTime = line.substring(idx1+1, idx2);
                                final String active = line.substring(idx2+1,  idx3);
                                final String modId = line.substring(idx3+1, idx4);
                                final String conceptId1 = line.substring(idx4 + 1, idx5);
                                final String conceptId2 = line.substring(idx5 + 1, idx6);
                                final String relId = line.substring(idx7 + 1, idx8);
                                
                                List<RelationshipRow> l = allRows.get(id+"_"+modId);
                                if(l == null) {
                                    l = new ArrayList<RelationshipRow>();
                                    allRows.put(id+"_"+modId, l);
                                }
                                l.add(new RelationshipRow(id, effectiveTime, active, modId, conceptId1, conceptId2, "", 
                                        relId, "", ""));
                            }
                        } catch(IOException e) {
                            e.printStackTrace();
                            Assert.assertTrue(false);
                        } finally {
                            if(br != null) {
                                try { br.close(); } catch(Exception e) {}
                            }
                        }
                        
                        System.gc();
                        
                        System.out.println("Filtering rows");
                        // Remove old versions - has to be module-aware
                        List<RelationshipRow> filteredRows = 
                                new ArrayList<RelationshipRow>();
                        
                        for(String key : allRows.keySet()) {
                            List<RelationshipRow> rows = allRows.get(key);
                            int mostRecent = Integer.MIN_VALUE;
                            RelationshipRow theOne = null;
                            for(RelationshipRow row : rows) {
                                int time = Integer.parseInt(row.getEffectiveTime());
                                if(time > mostRecent) {
                                    mostRecent = time;
                                    theOne = row;
                                }
                            }
                            if(theOne.getActive().equals("1") && 
                                    theOne.getTypeId().equals(isAId)) {
                                filteredRows.add(theOne);
                            }
                        }
                        
                        allRows = null;
                        System.gc();
                        
                        System.out.println("Building canonical parents");
                        Map<String, Set<String>> canonicalParents = 
                                new TreeMap<String, Set<String>>();
                        
                        for(RelationshipRow row : filteredRows) {
                            Set<String> parents = canonicalParents.get(
                                    row.getSourceId());
                            if (parents == null) {
                                parents = new HashSet<String>();
                                canonicalParents.put(row.getSourceId(), parents);
                            }
                            parents.add(row.getDestinationId());
                        }
                        
                        compareWithCanonical(canonicalParents, no, isAId, problems);
                        
                        for (String problem : problems) {
                            System.err.println(problem);
                        }

                        Assert.assertTrue(problems.isEmpty());
                    }
                }
                
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
        
        protected void compareWithCanonical(Map<String, Set<String>> canonicalParents, 
                NormalisedOntology no, String isAId, 
                List<String> problems) {
            System.out.println("Build taxonomy from canonical table");

            final String top = "_top_";
            final String bottom = "_bottom_";
            Map<String, Set<String>> canonicalEquivs = 
                    new TreeMap<String, Set<String>>();
            Set<String> topSet = new HashSet<String>();
            topSet.add(top);
            canonicalEquivs.put(top, topSet);
            for (String key : canonicalParents.keySet()) {
                Set<String> eq = new TreeSet<String>();
                eq.add(key);
                canonicalEquivs.put(key, eq);
                Set<String> parents = canonicalParents.get(key);
                if (parents == null) {
                    // Create the equivalent set with key
                    Set<String> val = new TreeSet<String>();
                    val.add(key);
                    canonicalEquivs.put(key, val);
                    continue;
                }
                for (String parent : parents) {
                    Set<String> grandpas = canonicalParents.get(parent);
                    if (grandpas != null && grandpas.contains(key)) {
                        // Concepts are equivalent
                        Set<String> equivs1 = canonicalEquivs.get(parent);
                        if (equivs1 == null)
                            equivs1 = new TreeSet<String>();
                        equivs1.add(key);
                        equivs1.add(parent);
                        Set<String> equivs2 = canonicalEquivs.get(key);
                        if (equivs2 == null)
                            equivs2 = new TreeSet<String>();
                        equivs2.add(key);
                        equivs2.add(parent);
                        equivs1.addAll(equivs2);
                        canonicalEquivs.put(key, equivs1);
                        canonicalEquivs.put(parent, equivs1);
                    }
                }
            }
            
            // Compare canonical and classified
            Map<String, Node> tax = no.getTaxonomy();
            
            for (Object key : tax.keySet()) {
                
                String concept = null;
                if(key == au.csiro.ontology.model.NamedConcept.TOP) {
                    concept = top;
                } else if(key == au.csiro.ontology.model.NamedConcept.BOTTOM){
                    concept = bottom;
                } else {
                    concept = (String) key; 
                }
                
                Node ps = null;
                
                if(key instanceof String) {
                    ps = no.getEquivalents((String)key);
                } else if(key == NamedConcept.TOP) {
                    ps = no.getTopNode();
                } else if(key == NamedConcept.BOTTOM) {
                    ps = no.getBottomNode();
                }

                // Actual equivalents set
                Set<String> aeqs = new HashSet<String>();

                for (Object cid : ps.getEquivalentConcepts()) {
                    if(cid == NamedConcept.TOP)
                        aeqs.add(top);
                    else if(cid == NamedConcept.BOTTOM)
                        aeqs.add(bottom);
                    else
                        aeqs.add((String)cid);
                }

                // Actual parents set
                Set<String> aps = new HashSet<String>();
                Set<Node> parents = ps.getParents();
                for (Node parent : parents) {
                    for (Object pid : parent.getEquivalentConcepts()) {
                        if(pid == NamedConcept.TOP)
                            aps.add(top);
                        else if(pid == NamedConcept.BOTTOM)
                            aps.add(bottom);
                        else
                            aps.add((String)pid);
                    }
                }
                 
                // FIXME: BOTTOM is not connected and TOP is not assigned as a
                // parent of SNOMED_CT_CONCEPT
                if (bottom.equals(concept)
                        || "138875005".equals(concept))
                    continue;

                Set<String> cps = canonicalParents.get(concept);
                Set<String> ceqs = canonicalEquivs.get(concept);

                // Compare both sets
                if (cps == null) {
                    cps = Collections.emptySet();
                }

                if (cps.size() != aps.size()) {
                    problems.add("Problem with concept " + concept
                            + ": canonical parents size = " + cps.size() + " ("
                            + cps.toString() + ")" + " actual parents size = "
                            + aps.size() + " (" + aps.toString() + ")");
                    continue;
                }

                for (String s : cps) {
                    if (!aps.contains(s)) {
                        problems.add("Problem with concept " + concept
                                + ": parents do not contain concept " + s);
                    }
                }

                if (ceqs == null) {
                    ceqs = Collections.emptySet();
                }

                // Add the concept to its set of equivalents (every concept is
                // equivalent to itself)
                aeqs.add(concept);
                if (ceqs.size() != aeqs.size()) {
                    problems.add("Problem with concept " + concept
                            + ": canonical equivalents size = " + ceqs.size()
                            + " actual equivalents size = " + aeqs.size());
                }
                for (String s : ceqs) {
                    if (!aeqs.contains(s)) {
                        problems.add("Problem with concept " + concept
                                + ": equivalents do not contain concept " + s);
                    }
                }
            }

        }
    }
    
}
