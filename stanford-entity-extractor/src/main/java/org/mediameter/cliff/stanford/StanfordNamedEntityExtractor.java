package org.mediameter.cliff.stanford;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.kohsuke.MetaInfServices;
import org.mediameter.cliff.CliffConfig;
import org.mediameter.cliff.extractor.EntityExtractor;
import org.mediameter.cliff.extractor.ExtractedEntities;
import org.mediameter.cliff.extractor.OrganizationOccurrence;
import org.mediameter.cliff.extractor.PersonOccurrence;
import org.mediameter.cliff.extractor.SentenceLocationOccurrence;
import org.mediameter.cliff.places.substitutions.Blacklist;
import org.mediameter.cliff.places.substitutions.CustomSubstitutionMap;
import org.mediameter.cliff.places.substitutions.WikipediaDemonymMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bericotech.clavin.extractor.LocationOccurrence;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Triple;

/**
 */
@MetaInfServices(EntityExtractor.class)
public class StanfordNamedEntityExtractor implements EntityExtractor {

    public final static Logger logger = LoggerFactory.getLogger(StanfordNamedEntityExtractor.class);

    public static final String CUSTOM_SUBSTITUTION_FILE = "custom-substitutions.csv";
    public static final String LOCATION_BLACKLIST_FILE = "location-blacklist.txt";
    public static final String PERSON_TO_PLACE_FILE = "person-to-place-replacements.csv";
    
    // the actual named entity recognizer (NER) object
    private AbstractSequenceClassifier<CoreMap> namedEntityRecognizer;
        
    private WikipediaDemonymMap demonyms;
    private CustomSubstitutionMap customSubstitutions;
    private CustomSubstitutionMap personToPlaceSubstitutions;
    private Blacklist locationBlacklist;
    
    private Model model;
    
    // Don't change the order of this, unless you also change the default in the cliff.properties file
    public enum Model {
        ENGLISH_ALL_3CLASS, ENGLISH_CONLL_4CLASS, SPANISH_ANCORA, GERMAN_DEWAC
    }

    public String getName(){
        return "Standord CoreNLP NER";
    }

    public void initialize(CliffConfig config) throws ClassCastException, IOException, ClassNotFoundException{
        String modelToUse = config.getNerModelName();
        model = Model.valueOf(modelToUse);
        logger.info("Creating Standford NER with " + modelToUse);
        switch(model){
            case ENGLISH_ALL_3CLASS:
                initializeWithModelFiles("english.all.3class.caseless.distsim.crf.ser.gz", "english.all.3class.caseless.distsim.prop" );
                break;
            case ENGLISH_CONLL_4CLASS:
                initializeWithModelFiles("english.conll.4class.caseless.distsim.crf.ser.gz", "english.conll.4class.caseless.distsim.prop"); // makes it take about 30% longer :-(
                break;
            case SPANISH_ANCORA:
                initializeWithModelFiles("spanish.ancora.distsim.s512.crf.ser.gz", "spanish.ancora.distsim.s512.prop"); // not tested yet
                break;
            case GERMAN_DEWAC:
                initializeWithModelFiles("german.dewac_175m_600.crf.ser.gz", "german.dewac_175m_600.prop"); // not tested yet
                break;
        }
        demonyms = new WikipediaDemonymMap();
        customSubstitutions = new CustomSubstitutionMap(CUSTOM_SUBSTITUTION_FILE);
        locationBlacklist = new Blacklist(LOCATION_BLACKLIST_FILE);
        personToPlaceSubstitutions = new CustomSubstitutionMap(PERSON_TO_PLACE_FILE,false);
    }
    
    /**
     * Builds a {@link StanfordNamedEntityExtractor} by instantiating the 
     * Stanford NER named entity recognizer with a specified 
     * language model.
     * 
     * @param NERmodel                      path to Stanford NER language model
     * @param NERprop                       path to property file for Stanford NER language model
     * @throws IOException 
     * @throws ClassNotFoundException 
     * @throws ClassCastException 
     */
    //@SuppressWarnings("unchecked")
    private void initializeWithModelFiles(String NERmodel, String NERprop) throws IOException, ClassCastException, ClassNotFoundException {
        InputStream mpis = this.getClass().getClassLoader().getResourceAsStream("models/" + NERprop);
        Properties mp = new Properties();
        mp.load(mpis);
        namedEntityRecognizer = CRFClassifier.getClassifier("models/" + NERmodel, mp);
    }

    /**
     * Get extracted locations from a plain-text body.
     * 
     * @param textToParse                      Text content to perform extraction on.
     * @param manuallyReplaceDemonyms   Can slow down performance quite a bit
     * @return          All the entities mentioned
     */
    @Override
    public ExtractedEntities extractEntities(String textToParse, boolean manuallyReplaceDemonyms) {
        ExtractedEntities entities = new ExtractedEntities();

        if (textToParse==null || textToParse.length()==0){
            logger.warn("input to extractEntities was null or zero!");
            return entities; 
        }

        String text = textToParse;
        if(manuallyReplaceDemonyms){    // this is a noticeable performance hit
            logger.debug("Replacing all demonyms by hand");
            text = demonyms.replaceAll(textToParse);
        }
        
        // extract entities as <Entity Type, Start Index, Stop Index>
        List<Triple<String, Integer, Integer>> extractedEntities = 
                namedEntityRecognizer.classifyToCharacterOffsets(text);

        if (extractedEntities != null) {
            for (Triple<String, Integer, Integer> extractedEntity : extractedEntities) {
                String entityName = text.substring(extractedEntity.second(), extractedEntity.third());
                int position = extractedEntity.second();
                switch(extractedEntity.first){
                case ":PERS":       // spanish
                case ":I-PER":      // german
                case "PERSON":      // english
                    if(personToPlaceSubstitutions.contains(entityName)){
                        entities.addLocation( getLocationOccurrence(personToPlaceSubstitutions.getSubstitution(entityName), position) );
                        logger.debug("Changed person "+entityName+" to a place");
                    } else {
                        PersonOccurrence person = new PersonOccurrence(entityName, position);
                        entities.addPerson( person );
                    }
                    break;
                case ":LUG":        // spanish
                case ":I-LOC":      // german
                case "LOCATION":    // english
                    if(!locationBlacklist.contains(entityName)){
                        entities.addLocation( getLocationOccurrence(entityName, position) );
                    } else {
                       logger.debug("Ignored blacklisted location "+entityName);
                    }
                    break;
                case ":ORG":            // spanish
                case ":I-ORG":          // german
                case "ORGANIZATION":    // english
                    OrganizationOccurrence organization = new OrganizationOccurrence(entityName, position);
                    entities.addOrganization( organization );
                    break;
                case "MISC":    // if you're using the slower 4class model
                    if (demonyms.contains(entityName)) {
                        logger.debug("Found and adding a MISC demonym "+entityName);
                        entities.addLocation( getLocationOccurrence(entityName, position) );
                    }
                    break;
                default:
                    logger.error("Unknown NER type :"+ extractedEntity.first);
                }
            }
        }

        return entities;
    }
    

    /**
     * Get extracted locations from a plain-text body.
     * 
     * @param sentences                      Text content to perform extraction on.
     * @param manuallyReplaceDemonyms   Can slow down performance quite a bit
     * @return          All the entities mentioned
     */
    @Override
    @SuppressWarnings("rawtypes")
    public ExtractedEntities extractEntitiesFromSentences(Map[] sentences, boolean manuallyReplaceDemonyms) {
        ExtractedEntities entities = new ExtractedEntities();

        if (sentences.length==0){
            logger.warn("input to extractEntities was null or zero!");
            return entities; 
        }

        if(manuallyReplaceDemonyms){    // this is a noticeable performance hit
            logger.debug("Replacing all demonyms by hand");
        }
        
        for(Map s:sentences){
            String storySentencesId = s.get("story_sentences_id").toString();
            String text = s.get("sentence").toString();
            if(manuallyReplaceDemonyms){    // this is a noticeable performance hit
                text = demonyms.replaceAll(text);
            }
            // extract entities as <Entity Type, Start Index, Stop Index>
            List<Triple<String, Integer, Integer>> extractedEntities = 
                namedEntityRecognizer.classifyToCharacterOffsets(text);
            if (extractedEntities != null) {
                for (Triple<String, Integer, Integer> extractedEntity : extractedEntities) {
                    String entityName = text.substring(extractedEntity.second(), extractedEntity.third());
                    int position = extractedEntity.second();
                    switch(extractedEntity.first){
                    case "PERSON":
                        if(personToPlaceSubstitutions.contains(entityName)){
                            entities.addLocation( getLocationOccurrence(personToPlaceSubstitutions.getSubstitution(entityName), position) );
                            logger.debug("Changed person "+entityName+" to a place");
                        } else {
                            PersonOccurrence person = new PersonOccurrence(entityName, position);
                            entities.addPerson( person );
                        }
                        break;
                    case "LOCATION":
                        if(!locationBlacklist.contains(entityName)){
                            LocationOccurrence loc = getLocationOccurrence(entityName, position);  
                            // save the sentence id here
                            entities.addLocation( new SentenceLocationOccurrence(loc.getText(), storySentencesId) );
                        } else {
                           logger.debug("Ignored blacklisted location "+entityName);
                        }
                        break;
                    case "ORGANIZATION":
                        OrganizationOccurrence organization = new OrganizationOccurrence(entityName, position);
                        entities.addOrganization( organization );
                        break;
                    case "MISC":    // if you're using the slower 4class model
                        if (demonyms.contains(entityName)) {
                            logger.debug("Found and adding a MISC demonym "+entityName);
                            entities.addLocation( getLocationOccurrence(entityName, position) );
                        }
                        break;
                    default:
                        logger.error("Unknown NER type :"+ extractedEntity.first);
                    }
                }
            }
        }

        return entities;
    }
    
    private LocationOccurrence getLocationOccurrence(String entityName, int position){
        String fixedName = entityName;
        if (demonyms.contains(entityName)) {
            fixedName = demonyms.getSubstitution(entityName); 
            logger.debug("Demonym substitution: "+entityName+" to "+fixedName);
        } else if(customSubstitutions.contains(entityName)) {
            fixedName = customSubstitutions.getSubstitution(entityName);
            logger.debug("Custom substitution: "+entityName+" to "+fixedName);
        }
        return new LocationOccurrence(fixedName, position);
    }
    
}
